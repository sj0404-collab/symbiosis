// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Подмена импортируемой функции в таблице переходов загруженной библиотеки.
//
// ПОЧЕМУ ЭТО ПРОВЕРЯЕТСЯ НА ХОСТЕ
//   На телефоне эта правка — единственный способ назвать виновника abort():
//   перехват по имени не сработал, и отчёт пришёл без единого кадра выше
//   служебной функции. Здесь проверяется та же механика на настоящей
//   библиотеке, у которой `abort` действительно идёт через `.plt`: если слот
//   находится и подменяется, значит и на устройстве найдётся. Ошибка в разборе
//   ELF стоит полного круга сборки и ещё одного отчёта без причины — поэтому
//   проверяем здесь.
//
//   Библиотека-жертва собирается тут же, из кода в этом файле: иначе тест
//   зависел бы от того, что кто-то положил готовый `.so` в репозиторий.

#include <csetjmp>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

#include <dlfcn.h>

#include "common/symbiosis/got_patch.h"

static int passed = 0;
static int failed = 0;
static std::jmp_buf escape;
static int fake_abort_calls = 0;

extern "C" void FakeAbort() {
    ++fake_abort_calls;
    std::longjmp(escape, 1);
}

static void check(const char* what, bool ok, const std::string& detail = "") {
    if (ok) {
        ++passed;
    } else {
        ++failed;
        std::printf("FAIL  %s%s%s\n", what, detail.empty() ? "" : " -> ", detail.c_str());
    }
}

/** Собрать библиотеку-жертву: она импортирует abort через .plt. */
static std::string BuildVictim() {
    const char* dir = std::getenv("TMPDIR");
    const std::string base = std::string(dir != nullptr ? dir : "/tmp") + "/symbiosis_got_test";
    std::system((std::string("mkdir -p ") + base).c_str());

    const std::string src = base + "/victim.cpp";
    const std::string so = base + "/libvictim.so";
    if (FILE* f = std::fopen(src.c_str(), "w")) {
        // <cstdlib> обязателен: без него в сгенерированном файле нет
        // объявления abort, и сборка жертвы молча падала — тест сообщал
        // «нет g++», хотя дело было в самом файле.
        std::fputs("#include <cstdlib>\nextern \"C\" int trigger() { std::abort(); return 1; }\n", f);
        std::fclose(f);
    }

    const std::string compile =
        "g++ -std=c++20 -fPIC -shared -O1 -o " + so + " " + src + " 2>/dev/null";
    if (std::system(compile.c_str()) != 0) {
        return "";
    }
    return so;
}

int main() {
    // ── адреса из .dynamic: глава, на которой всё и упало ────────────────
    //
    // glibc дописывает базу прямо в .dynamic, bionic — нет. Код, проверенный
    // только на хосте, читал смещение 0x47328 как адрес и падал на телефоне
    // (сборка 92888aa, SIGSEGV в PatchImport). Здесь проверяются оба вида.
    const unsigned long long base = 0x7a00000000ULL;
    const Symbiosis::LoadSpan spans[2] = {
        {base, base + 0x1000},              // заголовки и .dynamic
        {base + 0x10000, base + 0x90000},   // код и данные
    };
    check("абсолютный адрес принят как есть",
          Symbiosis::ImageAddress(base, base + 0x10020, spans, 2) == base + 0x10020);
    check("смещение превращено в адрес (bionic)",
          Symbiosis::ImageAddress(base, 0x10020, spans, 2) == base + 0x10020);
    // 0x47328 — настоящее значение с устройства, но оно попадает ВНУТРЬ
    // образа, поэтому и принято как смещение; вне образа — другое число.
    check("смещение внутри образа принято (как на устройстве)",
          Symbiosis::ImageAddress(base, 0x47328, spans, 2) == base + 0x47328);
    check("чужой адрес отвергнут",
          Symbiosis::ImageAddress(base, 0x1000000ULL, spans, 2) == 0);
    check("нулевая база не ломает разбор",
          Symbiosis::ImageAddress(0, 0x10020, spans, 2) == 0);
    check("без областей — ноль", Symbiosis::ImageAddress(base, base, nullptr, 0) == 0);

    const std::string so = BuildVictim();
    if (so.empty()) {
        std::printf("SKIP  библиотека-жертва не собралась (нет g++)\n");
        return 0;
    }

    void* handle = dlopen(so.c_str(), RTLD_NOW | RTLD_LOCAL);
    check("библиотека-жертва загружена", handle != nullptr, dlerror() ? dlerror() : "");
    if (handle == nullptr) {
        std::printf("passed=%d failed=%d\n", passed, failed);
        return 1;
    }
    auto* trigger = reinterpret_cast<int (*)()>(dlsym(handle, "trigger"));
    check("функция trigger найдена", trigger != nullptr);

    // 1. Настоящая подмена: без неё trigger() завершил бы процесс.
    const auto patched = Symbiosis::PatchImport(reinterpret_cast<const void*>(trigger), "abort",
                                                reinterpret_cast<void*>(&FakeAbort));
    check("abort найден в таблице переходов", patched.ok, patched.detail);
    check("слот ровно один", patched.slots == 1, std::to_string(patched.slots));

    if (patched.ok) {
        if (setjmp(escape) == 0) {
            trigger();   // должно уйти в FakeAbort
            check("abort был подменён, а не вызван", false);
        } else {
            check("подмена поймала abort", fake_abort_calls == 1, std::to_string(fake_abort_calls));
        }
    }

    // 2. Имени нет — это ответ с причиной, а не падение.
    const auto missing = Symbiosis::PatchImport(reinterpret_cast<const void*>(trigger),
                                                "функции_с_таким_именем_нет",
                                                reinterpret_cast<void*>(&FakeAbort));
    check("отсутствующая функция названа", !missing.ok, missing.detail);
    check("причина сформулирована", missing.detail.find("нет") != std::string::npos, missing.detail);

    // 3. Пустые аргументы не роняют процесс.
    const auto empty = Symbiosis::PatchImport(nullptr, "abort", nullptr);
    check("пустые аргументы пережиты", !empty.ok, empty.detail);

    // 4. Библиотека по символу из libc: слотов abort там нет, но и падения нет.
    const auto foreign = Symbiosis::PatchImport(dlsym(RTLD_DEFAULT, "malloc"), "abort",
                                                reinterpret_cast<void*>(&FakeAbort));
    check("чужая библиотека обработана", !foreign.ok, foreign.detail);

    dlclose(handle);
    std::printf("passed=%d failed=%d\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
