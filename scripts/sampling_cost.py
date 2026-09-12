#!/usr/bin/env python3
"""What sampling costs, or a refusal to say.

THE STAND COMES BEFORE THE NUMBER, and this file is why. The first attempt at the figure the brief
asks for - "sampling costs X% of CPU at 100 Hz" - was taken on a machine whose own run-to-run spread
was 24% of its median, in which the sampled runs came out FASTER than the unsampled ones in four
pairs out of seven. A percentage from that stand would have been a hypothesis wearing a suit.

So this script proves it can resolve a difference before it reports one. It asks the probe to do a
deliberate, known amount of extra work, and if it cannot see that, it prints what it saw and exits
non-zero rather than going on to measure something smaller.

WHAT IT DOES ABOUT NOISE, in the order of how much each is worth:

* **Both halves of a pair run in ONE process**, seconds apart. Two separate runs of the binary are
  two scheduling decisions, two page-cache states and two moments in whatever else the machine is
  doing; measured that way this stand saw a 30% spread and could not resolve a deliberate 2%.
* **The order alternates.** Whichever half runs second inherits a warmed allocator, and that bias is
  invisible until it is the entire signal.
* **One pinned core**, because a workload migrating between cores pays cache misses that have
  nothing to do with what is being measured.
* **CPU time from inside the process**, so that process start-up and the shell are not in the number.
* **The control spread is printed beside every result.** A reader who cannot see the noise cannot
  judge the signal, and a later run on a noisier machine must not look like this one.

Hardware counters would beat time and are not available here: perf on this kernel answers "perf not
found for kernel 6.6.87.2-microsoft". Said out loud rather than silently worked around.
"""

from __future__ import annotations

import argparse
import re
import statistics
import subprocess
import sys

FIELD = {name: re.compile(name + r"=(\d+)") for name in ("a_ms", "b_ms", "taken", "dropped")}


def pair(probe: str, rounds: int, hz: int, extra: int, swap: bool, cpu: int | None) -> dict[str, int]:
    """One pair, both halves in one process."""
    command = ["taskset", "-c", str(cpu)] if cpu is not None else []
    command += [probe, "ab", str(rounds), str(hz), str(extra)] + (["swap"] if swap else [])
    out = subprocess.run(command, capture_output=True, text=True, timeout=900).stdout
    found = {name: pattern.search(out) for name, pattern in FIELD.items()}
    if not all(found.values()):
        raise SystemExit("the probe printed something this stand cannot read:\n" + out)
    return {name: int(match.group(1)) for name, match in found.items()}


def paired(probe: str, cpu: int | None, pairs: int, rounds: int, hz: int, extra: int):
    """Pairs, alternating which half runs first."""
    a_series, b_series, deltas, taken, dropped = [], [], [], [], []
    for i in range(pairs):
        r = pair(probe, rounds, hz, extra, swap=(i % 2 == 1), cpu=cpu)
        a_series.append(r["a_ms"])
        b_series.append(r["b_ms"])
        deltas.append(r["b_ms"] - r["a_ms"])
        taken.append(r["taken"])
        dropped.append(r["dropped"])
    return a_series, b_series, deltas, taken, dropped


def spread(series: list[int]) -> float:
    """How wide the noise is, as a share of the median. The number that decides whether to believe."""
    return (max(series) - min(series)) / statistics.median(series)


def report(title: str, a: list[int], b: list[int], deltas: list[int]) -> float:
    median_a = statistics.median(a)
    change = statistics.median(deltas) / median_a
    negative = sum(1 for d in deltas if d < 0)
    print("  " + title)
    print("    control  median %.0f ms, spread %.1f%% of its own median" % (median_a, spread(a) * 100))
    print("    variant  median %.0f ms" % statistics.median(b))
    print("    paired   median %+.0f ms = %+.2f%%, negative in %d of %d pairs"
          % (statistics.median(deltas), change * 100, negative, len(deltas)))
    return change


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--probe", required=True, help="the sampler probe binary, release build")
    ap.add_argument("--hz", type=int, default=1000, help="requested sampling rate")
    ap.add_argument("--rounds", type=int, default=100000, help="workload size per half")
    ap.add_argument("--pairs", type=int, default=15, help="pairs per comparison")
    ap.add_argument("--cpu", type=int, default=None, help="core to pin to; unpinned when absent")
    ap.add_argument("--ladder", default="2,5,10,20",
                    help="percentages of deliberate extra work; the stand reports the smallest it can see")
    args = ap.parse_args()

    where = ("pinned to core %d" % args.cpu) if args.cpu is not None else "UNPINNED"
    print("razves sampling-cost stand: %d pairs, %d rounds per half, %s"
          % (args.pairs, args.rounds, where))
    pair(args.probe, args.rounds, 0, 0, False, args.cpu)  # warm-up, thrown away on purpose

    # THE LADDER, and the whole reason this file exists. More of the same work is a cost known by
    # construction: the workload is linear in rounds, so rounds * (1 + p) costs p more. The stand
    # climbs until it sees one, and everything it says afterwards is bounded by the rung it stopped
    # on. A fixed threshold would be a number I chose; this one the machine chooses.
    print("\nwhat can this stand resolve?")
    resolution = None
    for rung in [int(x) for x in args.ladder.split(",") if x.strip()]:
        a, b, deltas, _, _ = paired(args.probe, args.cpu, args.pairs, args.rounds, 0, rung)
        seen = report("%d%% more work, sampling off on both sides" % rung, a, b, deltas)
        if rung / 100.0 * 0.5 <= seen <= rung / 100.0 * 1.8:
            resolution = rung / 100.0
            print("    -> resolved: %+.2f%% for a deliberate %d%%" % (seen * 100, rung))
            break
        print("    -> not resolved here")

    if resolution is None:
        print("\nREFUSED: this stand saw none of the deliberate costs in the ladder.")
        print("Quieten the machine, pin a core, or raise --rounds - but do not publish a cost from"
              " this run. What the machine was doing is part of the answer: check the load first.")
        return 1

    print("\nwhat does sampling cost?")
    a, b, deltas, taken, dropped = paired(args.probe, args.cpu, args.pairs, args.rounds, args.hz, 0)
    cost = report("sampling at %d Hz requested" % args.hz, a, b, deltas)

    delivered = [t / (ms / 1000.0) for t, ms in zip(taken, b) if ms > 0]
    per_sample_ns = (statistics.median(deltas) * 1e6) / max(statistics.median(taken), 1)
    print("    delivered %.0f Hz for %d requested, %.0f samples per half, %.0f dropped"
          % (statistics.median(delivered), args.hz, statistics.median(taken), statistics.median(dropped)))
    print("    -> %+.2f%% of CPU, %+.0f ns per sample" % (cost * 100, per_sample_ns))

    # A cost smaller than what the stand just proved it can see is a BOUND, not a measurement, and
    # the difference is the whole discipline: one is a number, the other is a sentence about a number.
    if abs(cost) < resolution:
        print("    (this stand resolves %.0f%%; the cost is smaller than that, so the honest form is"
              " 'under %.0f%% of CPU at %d Hz' rather than a figure)"
              % (resolution * 100, resolution * 100, args.hz))
    return 0


if __name__ == "__main__":
    sys.exit(main())
