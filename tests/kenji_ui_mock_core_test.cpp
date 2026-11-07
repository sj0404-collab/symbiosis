// Host-only integration with an actual dlopen-ed synthetic library.
#include "../patch/symbiosis/kenji_ui_response_gate.h"
#include <cassert>
#include <cstring>
#include <dlfcn.h>
using Gate = Symbiosis::KenjiUiResponseGate;

int main(int argc, char** argv) {
    assert(argc == 2);
    void* core = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    assert(core != nullptr);
    auto respond = reinterpret_cast<Gate::Response>(dlsym(core, "uiHandlerSetResponse"));
    auto last = reinterpret_cast<const char* (*)()>(dlsym(core, "testLastText"));
    auto accepted = reinterpret_cast<bool (*)()>(dlsym(core, "testLastAccepted"));
    assert(respond && last && accepted);
    Gate gate;
    auto generation = gate.activate(respond);
    assert(gate.send(generation, true, u8"Имя игрока 🎮"));
    assert(accepted() && std::strcmp(last(), u8"Имя игрока 🎮") == 0);
    assert(gate.send(generation, false, ""));
    assert(!accepted() && std::strcmp(last(), "") == 0);
    gate.disable();
    assert(dlclose(core) == 0);
    // After closing the handle, the gate must not call the old function.
    // The OS may keep a mapping resident; this test does not prove unmapping.
    assert(!gate.send(generation, true, "after unload"));
}
