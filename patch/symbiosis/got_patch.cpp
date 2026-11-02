// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include "common/symbiosis/got_patch.h"

#include <cstdint>
#include <cstring>

#include <dlfcn.h>
#include <elf.h>
#include <link.h>
#include <sys/mman.h>
#include <unistd.h>

namespace Symbiosis {

namespace {

#if defined(__aarch64__)
constexpr unsigned kJumpSlotReloc = R_AARCH64_JUMP_SLOT;
#elif defined(__x86_64__)
constexpr unsigned kJumpSlotReloc = R_X86_64_JUMP_SLOT;
#else
constexpr unsigned kJumpSlotReloc = 0;   // архитектура не поддержана
#endif

/** Сведения о найденном объекте: база, заголовок программы, число сегментов. */
struct ObjectInfo {
    const char* path = nullptr;
    uintptr_t base = 0;
    const ElfW(Phdr)* phdr = nullptr;
    ElfW(Half) phnum = 0;
};

/** Что ищем: имя файла (может быть пустым) и база из dladdr. */
struct Query {
    const char* path;
    uintptr_t base;
    ObjectInfo* out;
};

int FindObjectCallback(struct dl_phdr_info* info, size_t, void* data) {
    auto* want = static_cast<Query*>(data);
    const char* name = info->dlpi_name;
    bool match = false;
    if (name != nullptr && name[0] != '\0' && want->path != nullptr && want->path[0] != '\0') {
        // Имена могут быть полными путями; сравниваем хвост после последнего
        // разделителя, иначе результат зависит от того, как библиотеку
        // открыли.
        const char* slash = std::strrchr(name, '/');
        const char* short_name = slash != nullptr ? slash + 1 : name;
        const char* want_slash = std::strrchr(want->path, '/');
        const char* want_short = want_slash != nullptr ? want_slash + 1 : want->path;
        match = std::strcmp(short_name, want_short) == 0;
    }
    if (!match) {
        // Запасной признак — база из dladdr. Нужен для главного объекта (у
        // него пустое имя) и для случаев, когда имя у загрузчика не совпало
        // с тем, что вернул dladdr. База уникальна, поэтому путаницы нет.
        match = want->base != 0 && info->dlpi_addr == want->base;
    }
    if (!match) {
        return 0;
    }
    want->out->path = name;
    want->out->base = info->dlpi_addr;
    want->out->phdr = info->dlpi_phdr;
    want->out->phnum = info->dlpi_phnum;
    return 1;   // нашли — дальше не идём
}

/** Найти динамический сегмент в заголовках программы. */
const ElfW(Dyn)* FindDynamic(const ObjectInfo& object) {
    for (ElfW(Half) i = 0; i < object.phnum; ++i) {
        if (object.phdr[i].p_type == PT_DYNAMIC) {
            return reinterpret_cast<const ElfW(Dyn)*>(object.base + object.phdr[i].p_vaddr);
        }
    }
    return nullptr;
}

ElfW(Addr) DynValue(const ElfW(Dyn)* dyn, ElfW(Sxword) tag, bool* found) {
    for (const ElfW(Dyn)* d = dyn; d->d_tag != DT_NULL; ++d) {
        if (d->d_tag == tag) {
            if (found != nullptr) *found = true;
            return d->d_un.d_ptr;
        }
    }
    if (found != nullptr) *found = false;
    return 0;
}

/** Открыть страницу со слотом на запись и записать адрес. */
bool WriteSlot(uintptr_t slot, void* value) {
    const long page_size = ::sysconf(_SC_PAGESIZE);
    if (page_size <= 0) return false;
    const uintptr_t page = slot & ~static_cast<uintptr_t>(page_size - 1);
    if (::mprotect(reinterpret_cast<void*>(page), static_cast<size_t>(page_size),
                   PROT_READ | PROT_WRITE) != 0) {
        return false;
    }
    *reinterpret_cast<void**>(slot) = value;
    // Обратно в READ|WRITE, а не в READ. Страница со слотами переходов лежит
    // среди данных и по умолчанию доступна на запись; закрыть её — значит
    // уронить библиотеку позже, на первом же обращении к ещё не связанному
    // символу (проверено на хосте: процесс падал уже после успешной подмены).
    ::mprotect(reinterpret_cast<void*>(page), static_cast<size_t>(page_size),
               PROT_READ | PROT_WRITE);
    return true;
}

} // namespace

GotPatch PatchImport(const void* anySymbol, const char* name, void* replacement) {
    GotPatch result;
    if (kJumpSlotReloc == 0) {
        result.detail = "архитектура не поддержана";
        return result;
    }
    if (anySymbol == nullptr || name == nullptr || replacement == nullptr) {
        result.detail = "не переданы аргументы";
        return result;
    }

    Dl_info info{};
    if (::dladdr(anySymbol, &info) == 0 || info.dli_fname == nullptr) {
        result.detail = "не удалось определить библиотеку";
        return result;
    }

    ObjectInfo object;
    Query query{info.dli_fname, reinterpret_cast<uintptr_t>(info.dli_fbase), &object};
    if (::dl_iterate_phdr(FindObjectCallback, &query) == 0 || object.phdr == nullptr) {
        result.detail = "библиотека не найдена среди загруженных";
        return result;
    }

    const ElfW(Dyn)* dyn = FindDynamic(object);
    if (dyn == nullptr) {
        result.detail = "нет динамического сегмента";
        return result;
    }

    bool has_jmprel = false;
    bool has_symtab = false;
    bool has_strtab = false;
    const auto jmprel = DynValue(dyn, DT_JMPREL, &has_jmprel);
    const auto symtab = DynValue(dyn, DT_SYMTAB, &has_symtab);
    const auto strtab = DynValue(dyn, DT_STRTAB, &has_strtab);
    bool has_pltrelsz = false;
    const auto pltrelsz = DynValue(dyn, DT_PLTRELSZ, &has_pltrelsz);
    bool has_pltrel = false;
    const auto pltrel = DynValue(dyn, DT_PLTREL, &has_pltrel);
    if (!has_jmprel || !has_symtab || !has_strtab || !has_pltrelsz) {
        result.detail = "нет таблицы переходов";
        return result;
    }

    const bool is_rela = !has_pltrel || pltrel == DT_RELA;
    const auto* rela = reinterpret_cast<const ElfW(Rela)*>(jmprel);
    const auto* rel = reinterpret_cast<const ElfW(Rel)*>(jmprel);
    const auto* syms = reinterpret_cast<const ElfW(Sym)*>(symtab);
    const auto* names = reinterpret_cast<const char*>(strtab);

    // Обходим ровно столько записей, сколько объявлено: за концом таблицы
    // начинаются чужие данные, и чтение «пока не найдём» тут же уходит в них.
    const std::size_t count = is_rela ? pltrelsz / sizeof(ElfW(Rela)) : pltrelsz / sizeof(ElfW(Rel));

    for (std::size_t i = 0; i < count; ++i) {
        const auto reloc_type = is_rela ? ELF64_R_TYPE(rela[i].r_info) : ELF64_R_TYPE(rel[i].r_info);
        if (reloc_type != kJumpSlotReloc) {
            continue;
        }
        const auto sym_index = is_rela ? ELF64_R_SYM(rela[i].r_info) : ELF64_R_SYM(rel[i].r_info);
        const char* sym_name = names + syms[sym_index].st_name;
        if (std::strcmp(sym_name, name) != 0) {
            continue;
        }
        const uintptr_t slot = object.base + (is_rela ? rela[i].r_offset : rel[i].r_offset);
        if (!WriteSlot(slot, replacement)) {
            result.detail = "страница со слотом защищена от записи";
            return result;
        }
        ++result.slots;
    }

    if (result.slots == 0) {
        result.detail = std::string("в таблице переходов нет ") + name;
        return result;
    }
    result.ok = true;
    result.detail = std::string("подменено слотов: ") + std::to_string(result.slots);
    return result;
}

} // namespace Symbiosis
