#!/usr/bin/env python3
from math import hypot
from sys import argv
from typing import List, Dict, Tuple


def main(fn, maxD):
    with open(fn) as file:
        positions: List[str] = file.readlines()

    uLocations: Dict[int, Dict[str, Tuple[int, int]]] = {}
    for position in positions:
        positionSplit = position.split(';')
        if int(positionSplit[1]) not in uLocations:
            uLocations[int(positionSplit[1])] = {}
        uLocations[int(positionSplit[1])][positionSplit[0]] = (int(positionSplit[2]), int(positionSplit[3]))

    for epoch in uLocations.keys():
        print(f"Epoch: {epoch}")
        for uname, location in uLocations[epoch].items():
            closeUnames = []
            for iUname, iLocation in uLocations[epoch].items():
                if uname == iUname:
                    continue
                distance = hypot(iLocation[0] - location[0], iLocation[1] - location[1])
                if distance < maxD:
                    closeUnames.append(iUname)
            print(f"{uname}: {closeUnames}")


if __name__ == '__main__':
    if len(argv) < 3:
        print("Usage: [Grid Filename] [Max Distance]")
    else:
        main(argv[1], float(argv[2]))
