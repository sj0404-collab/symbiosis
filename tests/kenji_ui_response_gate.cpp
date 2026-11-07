// Host-only tests. The functions below are synthetic, not a game or a core.
#include "../patch/symbiosis/kenji_ui_response_gate.h"
#include <atomic>
#include <cassert>
#include <chrono>
#include <future>
#include <mutex>
#include <string>
#include <thread>
using namespace std::chrono_literals;
using Gate = Symbiosis::KenjiUiResponseGate;

static std::mutex record_lock;
static std::string record;
static bool accepted_value = false;
static std::atomic<int> calls{0};
static std::promise<void>* arrived = nullptr;
static std::shared_future<void> release_response;

static void normal_response(bool accepted, const char* utf8) {
    std::lock_guard<std::mutex> guard(record_lock);
    accepted_value = accepted;
    record = utf8;
    ++calls;
}
static void slow_response(bool, const char*) {
    arrived->set_value();
    release_response.wait();
    ++calls;
}

int main() {
    Gate gate;
    // No real core, device, JNI or Android is exercised in this test.
    assert(!gate.send(0, true, "no core"));
    const auto first = gate.activate(normal_response);
    assert(gate.send(first, true, u8"Привет — 🌍"));
    assert(accepted_value && record == u8"Привет — 🌍");
    assert(gate.send(first, false, ""));
    assert(!accepted_value && record.empty());
    assert(!gate.send(first, true, nullptr));
    gate.disable();
    assert(!gate.send(first, true, "late"));
    const auto second = gate.activate(normal_response);
    assert(first != second && !gate.send(first, true, "stale activity"));
    assert(gate.send(second, true, "new session"));

    // A synthetic loader holds its own lock while waiting for an answer. The
    // response must be able to enter without taking that lock. A production
    // JNI wrapper would deadlock if it took the loader lock here.
    std::mutex loader_lock;
    std::promise<void> entered, answer;
    auto answer_future = answer.get_future().share();
    std::thread loader([&] {
        std::lock_guard<std::mutex> guard(loader_lock);
        entered.set_value();
        answer_future.wait();
    });
    entered.get_future().wait();
    std::unique_lock<std::mutex> attempt(loader_lock, std::try_to_lock);
    assert(!attempt.owns_lock());
    assert(gate.send(second, true, "answer while loader owns lock"));
    answer.set_value();
    loader.join();

    // Disable must wait for an actual in-flight call, and no later callback
    // may enter after disable completes (important before dlclose).
    std::promise<void> in_response, release;
    arrived = &in_response;
    release_response = release.get_future().share();
    const auto third = gate.activate(slow_response);
    std::thread response([&] { assert(gate.send(third, true, "ok")); });
    in_response.get_future().wait();
    std::atomic<bool> disabled{false};
    std::promise<void> closer_started;
    std::thread closer([&] { closer_started.set_value(); gate.disable(); disabled = true; });
    closer_started.get_future().wait();
    std::this_thread::sleep_for(30ms);
    assert(!disabled.load());
    release.set_value();
    response.join(); closer.join();
    assert(disabled && !gate.send(third, true, "late"));

    // The gate accepts either order of response and disable, never a call
    // through a pointer after disable has returned.
    for (int i = 0; i < 300; ++i) {
        auto id = gate.activate(normal_response);
        std::atomic<bool> done{false};
        std::thread sender([&] { gate.send(id, true, "race"); });
        std::thread disabler([&] { gate.disable(); done = true; });
        sender.join(); disabler.join();
        assert(done && !gate.send(id, true, "after"));
    }
}
