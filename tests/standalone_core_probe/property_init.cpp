// Diagnostic-only: create Bionic's real shared property area on a disposable
// ARM Linux runner, populated exclusively from the pinned Android image's
// build.prop. Not a service, not an APK, not a source of phone properties.
#include <dlfcn.h>
#include <sys/system_properties.h>
#include <cstdio>
#include <cstring>

int main(int argc, char** argv) {
    if (argc != 2) { std::fprintf(stderr, "usage: property-init image-build.prop\n"); return 2; }
    using Init = int (*)();
    using Add = int (*)(const char*, unsigned, const char*, unsigned);
    auto init = reinterpret_cast<Init>(dlsym(RTLD_DEFAULT, "__system_property_area_init"));
    auto add = reinterpret_cast<Add>(dlsym(RTLD_DEFAULT, "__system_property_add"));
    if (!init || !add) { std::fprintf(stderr, "PROPERTY_INIT_FAIL missing Bionic property exports\n"); return 3; }
    FILE* file = std::fopen(argv[1], "r");
    if (!file) { std::perror("PROPERTY_INIT_FAIL image-build.prop"); return 4; }
    const int result = init();
    std::printf("PROPERTY_AREA_INIT=%d\n", result);
    // Android 8.1 Bionic returns -2 when the area was created but Linux could
    // not set SELinux xattrs. Do not claim a full Android property service.
    if (result != 0 && result != -2) { std::fclose(file); return 5; }
    char line[1024];
    int count = 0;
    bool have_abi = false, have_sdk = false;
    while (std::fgets(line, sizeof(line), file)) {
        if (line[0] == '#' || line[0] == '\n' || line[0] == '\r') continue;
        char* equal = std::strchr(line, '=');
        if (!equal) continue;
        *equal++ = '\0';
        equal[std::strcspn(equal, "\r\n")] = '\0';
        const bool selected = !std::strncmp(line, "ro.product.cpu.", 15) ||
                              !std::strncmp(line, "dalvik.vm.", 10) ||
                              !std::strcmp(line, "ro.build.version.sdk") ||
                              !std::strcmp(line, "ro.build.version.release") ||
                              !std::strcmp(line, "persist.sys.dalvik.vm.lib.2");
        if (!selected) continue;
        if (std::strlen(equal) >= PROP_VALUE_MAX) { std::fprintf(stderr, "PROPERTY_VALUE_TOO_LONG %s\n", line); std::fclose(file); return 6; }
        if (add(line, std::strlen(line), equal, std::strlen(equal)) != 0) {
            std::fprintf(stderr, "PROPERTY_ADD_FAIL %s\n", line); std::fclose(file); return 7;
        }
        std::printf("IMAGE_PROPERTY %s=%s\n", line, equal);
        if (!std::strcmp(line, "ro.product.cpu.abilist64") && equal[0]) have_abi = true;
        if (!std::strcmp(line, "ro.build.version.sdk") && equal[0]) have_sdk = true;
        ++count;
    }
    std::fclose(file);
    if (!have_abi || !have_sdk) { std::fprintf(stderr, "PROPERTY_REQUIRED_MISSING\n"); return 8; }
    std::printf("PROPERTY_IMAGE_VALUES_ADDED=%d\n", count);
    return 0;
}
