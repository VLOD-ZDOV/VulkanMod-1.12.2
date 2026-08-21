#!/usr/bin/env python3
"""Compare two frames from an automated flight.

Why this exists
---------------

The `spin` route ends at the angle it started at, so its first and last frames
are pictures of the same view. Anything keyed to the screen rather than to the
world differs between them, and nothing else does — which turns "the grass
darkens when I turn" from something to argue about into a number.

It is also the A/B for a change: fly the same route twice, once on each build,
and compare frame for frame.

What it prints
--------------

The mean absolute difference over all channels, the largest single difference,
and the share of pixels past three thresholds. A difference of one or two is
the card rounding; anything a person can see is well past eight.

Usage
-----

    python3 tools/flight-compare.py a.png b.png [--out diff.png] [--skip-top 0.12]

`--skip-top` drops that fraction of the height before comparing, which is how
to ignore the game's debug screen when a run was flown with it up.
"""

import argparse
import sys

import numpy

try:
    from PIL import Image, ImageChops
except ImportError:  # pragma: no cover - depends on the machine, not the code
    sys.exit("This needs Pillow: pip install --user pillow")


def load(path, skip_top):
    image = Image.open(path).convert("RGB")
    if skip_top > 0.0:
        top = int(image.height * skip_top)
        image = image.crop((0, top, image.width, image.height))
    return image


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("first")
    parser.add_argument("second")
    parser.add_argument("--out", help="write an amplified difference image here")
    parser.add_argument("--skip-top", type=float, default=0.0,
                        help="ignore this fraction of the height, for the debug screen")
    args = parser.parse_args()

    a = load(args.first, args.skip_top)
    b = load(args.second, args.skip_top)
    if a.size != b.size:
        sys.exit("Different sizes: %s vs %s" % (a.size, b.size))

    diff = ImageChops.difference(a, b)
    values = numpy.asarray(diff, dtype=numpy.int16)
    worst = values.max(axis=2)
    count = worst.size
    print("size            %d x %d" % a.size)
    print("mean difference %.3f of 255" % values.mean())
    print("largest         %d of 255" % int(worst.max()))
    over2 = int((worst > 2).sum())
    over8 = int((worst > 8).sum())
    over32 = int((worst > 32).sum())
    print("pixels over 2   %.2f%%" % (100.0 * over2 / count))
    print("pixels over 8   %.2f%%" % (100.0 * over8 / count))
    print("pixels over 32  %.2f%%" % (100.0 * over32 / count))
    largest = int(worst.max())

    if args.out:
        # Multiplied up, because a difference worth chasing is often four or
        # five levels and four or five levels is black on a screen.
        Image.eval(diff, lambda v: min(255, v * 8)).save(args.out)
        print("difference      %s (multiplied by eight)" % args.out)

    # A verdict rather than a table: the point of running this unattended is
    # that something reads the result without a person in the loop.
    if largest <= 2:
        print("verdict         identical")
    elif over8 == 0:
        print("verdict         rounding only")
    else:
        print("verdict         REAL DIFFERENCE")


if __name__ == "__main__":
    main()
