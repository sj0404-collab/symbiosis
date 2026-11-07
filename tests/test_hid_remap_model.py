#!/usr/bin/env python3
"""Simplified hypothesis model of a RELATED NpadDevices.cs, not Kenji or Android.

One slot, initially no style/input. Only Pro, Pair and Handheld are modeled.
When style exists, 'connected' is the latest sample in that style's common
LIFO; it does not represent SystemExt or every shared-memory field.
"""
from collections import Counter
from itertools import product
import unittest

PRO, PAIR, HAND = 'pro', 'pair', 'handheld'


def choose(config, supported, player_allowed, docked):
    # Related NpadDevices.Remap; it does not check ARUID or return error 710.
    if config == HAND and docked:
        config = None
    if config == PAIR and PAIR not in supported and PRO in supported:
        config = PRO
    elif config == PRO and PRO not in supported and PAIR in supported:
        config = PAIR
    if config not in supported or not player_allowed:
        config = None
    return config


class Slot:
    def __init__(self):
        self.style = None
        self.connected = False
        self.log = []

    def tick(self, config, supported, player_allowed, docked, state_listed):
        new_style = choose(config, supported, player_allowed, docked)
        if new_style != self.style:
            # SetupNpad resets the slot, then logs on style change.
            self.connected = False
            self.log.append(('connected' if new_style else 'disconnected', new_style))
            self.style = new_style
        # Model only the current selected style's latest common entry, which
        # UpdateInput writes IsConnected; absent state writes a zero entry.
        if self.style is not None:
            self.connected = state_listed


class HidRemapModelTest(unittest.TestCase):
    def test_512_combinations_from_empty_state(self):
        outcomes = Counter()
        for config, bits, allowed, docked, listed, update in product(
            (None, PRO, PAIR, HAND), range(8), (False, True),
            (False, True), (False, True), (False, True)
        ):
            supported = {x for bit, x in enumerate((PRO, PAIR, HAND)) if bits & (1 << bit)}
            slot = Slot()
            if update:
                slot.tick(config, supported, allowed, docked, listed)
            if slot.style is None:
                self.assertFalse(slot.connected)
                outcomes['no_style'] += 1
            elif slot.connected:
                outcomes['style_and_connected'] += 1
                self.assertTrue(update and listed)
            else:
                outcomes['style_without_connected'] += 1
                self.assertTrue(update and not listed)
        self.assertEqual(sum(outcomes.values()), 512)
        self.assertEqual(outcomes, {'no_style': 456, 'style_and_connected': 28, 'style_without_connected': 28})

    def test_late_list_change_requires_another_update_in_this_model(self):
        s = Slot()
        s.tick(PRO, {PRO, PAIR, HAND}, True, False, True)
        self.assertEqual((s.style, s.connected, s.log[-1][0]), (PRO, True, 'connected'))
        # Simulate list replacement by the guest; before another tick the
        # previously written sample is still the only modeled observation.
        new_supported = {HAND}
        self.assertEqual((s.style, s.connected), (PRO, True))
        s.tick(PRO, new_supported, True, False, True)
        self.assertEqual((s.style, s.connected, s.log[-1][0]), (None, False, 'disconnected'))
        self.assertEqual(s.log[0][0], 'connected')  # historical line remains

    def test_style_fallback_dock_and_missing_state(self):
        self.assertEqual(choose(PRO, {PAIR}, True, False), PAIR)
        self.assertEqual(choose(PAIR, {PRO}, True, False), PRO)
        self.assertIsNone(choose(HAND, {HAND}, True, True))
        self.assertIsNone(choose(PRO, {PRO}, False, False))
        s = Slot()
        s.tick(PRO, {PRO}, True, False, False)
        self.assertEqual((s.style, s.connected, s.log[0][0]), (PRO, False, 'connected'))
        s.tick(PRO, {PRO}, True, False, True)
        self.assertTrue(s.connected)
        # A stopped updater does not erase the previous modeled sample.
        self.assertTrue(s.connected)

if __name__ == '__main__':
    unittest.main()
