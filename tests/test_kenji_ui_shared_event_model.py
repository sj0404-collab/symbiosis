#!/usr/bin/env python3
"""Counterexamples for a related core's shared UI event (not a real core).

The related AndroidUIHandler uses one ManualResetEvent, one response value and
one input value for all requests. Model that specific property. These tests do
not establish that the pinned binaries have the same implementation.
"""
import threading
import unittest


class SharedEventModel:
    def __init__(self):
        self.event = threading.Event()  # manual reset
        self.accepted = False
        self.text = None

    def begin_input(self):
        self.text = None
        self.event.clear()

    def respond(self, accepted, text):
        self.accepted = accepted
        self.text = text
        self.event.set()

    def result(self):
        if not self.event.wait(2):
            raise AssertionError('test failed to release mock guest')
        return self.accepted, self.text or ''


class SharedEventRiskTest(unittest.TestCase):
    def test_sequential_inputs_can_receive_distinct_responses(self):
        handler = SharedEventModel()
        handler.begin_input()
        handler.respond(True, 'one')
        self.assertEqual(handler.result(), (True, 'one'))
        handler.begin_input()
        handler.respond(True, 'two')
        self.assertEqual(handler.result(), (True, 'two'))

    def test_one_response_wakes_two_overlapping_synthetic_guests(self):
        handler = SharedEventModel()
        barrier = threading.Barrier(3)
        results = [None, None]
        errors = []

        def guest(index):
            try:
                handler.begin_input()
                barrier.wait(timeout=2)
                results[index] = handler.result()
            except BaseException as error:
                errors.append(error)

        workers = [threading.Thread(target=guest, args=(i,)) for i in range(2)]
        for thread in workers:
            thread.start()
        barrier.wait(timeout=2)  # both requests reset the single event
        handler.respond(True, 'answer for first')
        for thread in workers:
            thread.join(timeout=3)
            self.assertFalse(thread.is_alive())
        self.assertFalse(errors)
        self.assertEqual(results, [(True, 'answer for first')] * 2)
        # A queue in the Android UI alone cannot give the second guest its own
        # answer after the first response has already released both waiters.

    def test_late_message_response_can_wake_an_input_waiter(self):
        handler = SharedEventModel()
        # In the related source a message callback returns immediately; its
        # asynchronous UI answer may arrive only after an input resets event.
        handler.begin_input()
        handler.respond(True, '')  # delayed synthetic message confirmation
        self.assertEqual(handler.result(), (True, ''))
        # This is a possible schedule in this model, not a reported game bug.


if __name__ == '__main__':
    unittest.main()
