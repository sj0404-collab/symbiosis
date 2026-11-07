// Synthetic host-only dynamic library; never compiled into an Android APK.
#include <string>

static std::string last;
static bool last_accepted;
static int setup_count;

extern "C" void uiHandlerSetup() { ++setup_count; }
extern "C" int testSetupCount() { return setup_count; }
extern "C" void uiHandlerSetResponse(bool accepted, const char* text) {
    last_accepted = accepted;
    last = text;
}
extern "C" const char* testLastText() { return last.c_str(); }
extern "C" bool testLastAccepted() { return last_accepted; }
