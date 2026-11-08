/* Synthetic PID 1 for a kernel-only probe, never installed in Android or APK. */
#include <sys/auxv.h>
#include <sys/reboot.h>
#include <stdio.h>
#include <unistd.h>

int main(void) {
    const unsigned long required = 0x1182; /* ASIMD, CRC32, ATOMICS, ASIMDRDM */
    const unsigned long hw = getauxval(AT_HWCAP);
    const unsigned long page = getauxval(AT_PAGESZ);
    const long sys_page = sysconf(_SC_PAGESIZE);
    setvbuf(stdout, NULL, _IONBF, 0);
    printf("REAL_16K_PROBE AT_PAGESZ=%lu SYSCONF=%ld HWCAP=0x%lx REQUIRED=0x%lx MISSING=0x%lx\n",
           page, sys_page, hw, required, required & ~hw);
    sync();
    reboot(RB_POWER_OFF);
    for (;;) pause();
}
