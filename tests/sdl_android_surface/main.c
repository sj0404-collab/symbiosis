/* Disposable diagnostic: modern devkitPro SDL2 software window surface.
 * This is not included in any release APK and never loads user content. */
#include <switch.h>
#include <SDL.h>
#include <stdio.h>
#include <stdarg.h>
#include <string.h>

static void logline(const char *fmt, ...) {
    char buf[256];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    if (n < 0) return;
    size_t size = (size_t)n < sizeof(buf) ? (size_t)n : sizeof(buf)-1;
    svcOutputDebugString(buf, size);
}

int main(int argc, char **argv) {
    (void)argc; (void)argv;
    SDL_version version;
    SDL_GetVersion(&version);
    logline("SDL_SURFACE_START version=%u.%u.%u", version.major, version.minor, version.patch);
    if (SDL_Init(SDL_INIT_VIDEO) < 0) {
        logline("SDL_SURFACE_FAIL init=%s", SDL_GetError());
        return 1;
    }
    logline("SDL_SURFACE_INIT_OK driver=%s", SDL_GetCurrentVideoDriver());
    SDL_Window *w = SDL_CreateWindow("Diagnostic", SDL_WINDOWPOS_UNDEFINED,
            SDL_WINDOWPOS_UNDEFINED, 1280, 720, SDL_WINDOW_SHOWN);
    if (!w) { logline("SDL_SURFACE_FAIL window=%s", SDL_GetError()); SDL_Quit(); return 1; }
    logline("SDL_SURFACE_WINDOW_OK");
    SDL_Surface *surface = SDL_GetWindowSurface(w);
    if (!surface) { logline("SDL_SURFACE_FAIL get=%s", SDL_GetError()); SDL_DestroyWindow(w); SDL_Quit(); return 1; }
    logline("SDL_SURFACE_GET_OK width=%d height=%d pitch=%d", surface->w, surface->h, surface->pitch);
    for (int frame = 0; frame < 600 && appletMainLoop(); ++frame) {
        Uint32 color = SDL_MapRGBA(surface->format, (frame / 60) % 2 ? 20 : 36, 110, 190, 255);
        if (SDL_FillRect(surface, NULL, color) < 0) {
            logline("SDL_SURFACE_FAIL fill=%s", SDL_GetError()); break;
        }
        if (SDL_UpdateWindowSurface(w) < 0) {
            logline("SDL_SURFACE_FAIL present=%s", SDL_GetError()); break;
        }
        if (frame == 0 || frame == 59) logline("SDL_SURFACE_PRESENT_OK frame=%d", frame);
        SDL_Delay(16);
    }
    logline("SDL_SURFACE_LOOP_ENDED");
    SDL_DestroyWindow(w);
    SDL_Quit();
    return 0;
}
