#include <cassert>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <string>
#include <utility>
#include <vector>
#include "common/symbiosis/rom_tools.h"
using namespace Symbiosis;
namespace fs=std::filesystem;
const u64 MB=1024*1024;

void put(std::ofstream&f,u64 off,const void*d,size_t n){f.seekp(off);f.write((const char*)d,n);}

void mkxci(const std::string&p,u64 content,u64 total){
    std::ofstream f(p,std::ios::binary);
    f.seekp(total-1); f.put(0);           // задаём размер
    u32 magic=0x44414548; put(f,0x100,&magic,4);
    u64 units=content/0x200-1; put(f,0x118,&units,8);
}
int main(){
    fs::create_directories("/tmp/romtest");
    // 1. XCI с паддингом: 1 ГБ контента в файле 4 ГБ
    mkxci("/tmp/romtest/padded.xci", 1024*MB, 4096*MB);
    auto r=RomTools::Inspect("/tmp/romtest/padded.xci");
    printf("padded.xci -> %s / %s, reclaim=%llu MiB\n",ToString(r.format),ToString(r.health),
      (unsigned long long)(r.reclaimable/MB));
    assert(r.format==DumpFormat::Xci && r.health==DumpHealth::Padded);
    assert(r.reclaimable > 3000*MB);

    // Обрезка должна вернуть место
    std::string err;
    u64 freed=RomTools::TrimXci("/tmp/romtest/padded.xci",err);
    printf("trimmed: %llu MiB freed (err='%s')\n",(unsigned long long)(freed/MB),err.c_str());
    assert(freed>3000*MB);
    assert(fs::file_size("/tmp/romtest/padded.xci")==1024*MB);
    auto r2=RomTools::Inspect("/tmp/romtest/padded.xci");
    assert(r2.health==DumpHealth::Trimmed);
    printf("after trim -> %s\n",ToString(r2.health));

    // 2. Обрезанный (битый) дамп: заголовок обещает больше, чем есть
    mkxci("/tmp/romtest/broken.xci", 2048*MB, 500*MB);
    auto b=RomTools::Inspect("/tmp/romtest/broken.xci");
    printf("\nbroken.xci -> %s: %s\n",ToString(b.health),b.summary.c_str());
    assert(b.health==DumpHealth::Truncated);
    // Обрезать битый файл ЗАПРЕЩЕНО — иначе потеря данных
    u64 f2=RomTools::TrimXci("/tmp/romtest/broken.xci",err);
    printf("trim refused: %s\n",err.c_str());
    assert(f2==0);

    // 3. NSP
    { std::ofstream f("/tmp/romtest/game.nsp",std::ios::binary);
      u32 m=0x30534650,c=7; f.write((char*)&m,4); f.write((char*)&c,4); f.seekp(10*MB); f.put(0);}
    auto n=RomTools::Inspect("/tmp/romtest/game.nsp");
    printf("\ngame.nsp -> %s / %s: %s\n",ToString(n.format),ToString(n.health),n.summary.c_str());
    assert(n.format==DumpFormat::Nsp && n.health==DumpHealth::Good);

    // 4. NSZ — честно сообщаем что не поддерживается
    { std::ofstream f("/tmp/romtest/x.nsz",std::ios::binary); f.write("junk",4);}
    auto z=RomTools::Inspect("/tmp/romtest/x.nsz");
    printf("x.nsz -> %s, advice: %s\n",ToString(z.format),z.advice.substr(0,40).c_str());
    assert(z.format==DumpFormat::Nsz && !z.advice.empty());

    // Already-openable NSP: decompress is a copy, never a re-encode.
    {
        std::string err;
        u64 w = RomTools::Decompress("/tmp/romtest/game.nsp", "/tmp/romtest/out.nsp", err);
        printf("\ndecompress nsp copy: %llu (%s)\n", (unsigned long long)w, err.c_str());
        assert(w > 0);
        assert(fs::file_size("/tmp/romtest/out.nsp") == fs::file_size("/tmp/romtest/game.nsp"));
    }

    // 5. Почему ядро отказывает контейнеру.
    //
    // На устройстве распакованный NSP прошёл ReadProgramId и был отвергнут
    // проверкой IsBootableGameContainer, а в шторке осталось «ядро не
    // принимает этот файл». DescribeContainer обязан назвать звено: нет CNMT,
    // файл внутри не NCA, либо оглавление целое и причина в данных.
    auto mkpfs0 = [](const std::string& path, const std::vector<std::pair<std::string, std::string>>& files) {
        u32 string_bytes = 0;
        for (auto& f : files) string_bytes += (u32)f.first.size() + 1;
        std::ofstream out(path, std::ios::binary | std::ios::trunc);
        u32 magic = 0x30534650, count = (u32)files.size(), zero = 0;
        out.write((const char*)&magic, 4);
        out.write((const char*)&count, 4);
        out.write((const char*)&string_bytes, 4);
        out.write((const char*)&zero, 4);
        u64 rel = 0;
        u32 name_off = 0;
        for (auto& f : files) {
            u64 size = (u64)f.second.size();
            out.write((const char*)&rel, 8);
            out.write((const char*)&size, 8);
            out.write((const char*)&name_off, 4);
            out.write((const char*)&zero, 4);
            rel += size;
            name_off += (u32)f.first.size() + 1;
        }
        for (auto& f : files) { out.write(f.first.c_str(), (std::streamsize)f.first.size() + 1); }
        for (auto& f : files) { out.write(f.second.data(), (std::streamsize)f.second.size()); }
    };

    // Содержимое «NCA»: 0x200 байт ключевой области и метка по смещению 0x200.
    auto nca = [](const char* mark) {
        std::string body(0x400, '\0');
        std::memcpy(body.data() + 0x200, mark, 4);
        return body;
    };

    {
        // Нет CNMT — ядро не считает такой контейнер игрой.
        mkpfs0("/tmp/romtest/no_cnmt.nsp",
               {{"01004F302847E000.nca", nca("NCA3")}, {"01004F302847E001.nca", nca("NCA3")}});
        const auto why = RomTools::DescribeContainer("/tmp/romtest/no_cnmt.nsp");
        printf("\nno_cnmt -> %s\n", why.c_str());
        assert(why.find("cnmt") != std::string::npos);
        assert(why.find("01004F302847E000.nca") != std::string::npos);

        // Распаковка записала не NCA: метки NCA3 по 0x200 нет.
        mkpfs0("/tmp/romtest/fake_nca.nsp",
               {{"01004F302847E000.cnmt.nca", nca("NCA3")}, {"01004F302847E001.nca", "not an nca"}});
        const auto bad = RomTools::DescribeContainer("/tmp/romtest/fake_nca.nsp");
        printf("fake_nca -> %s\n", bad.c_str());
        assert(bad.find("не похож на NCA") != std::string::npos);
        assert(bad.find("распаковки") != std::string::npos);

        // Структура целая: причина не в оглавлении, и это тоже надо сказать.
        mkpfs0("/tmp/romtest/fine.nsp",
               {{"01004F302847E000.cnmt.nca", nca("NCA3")}, {"01004F302847E001.nca", nca("NCA3")}});
        const auto fine = RomTools::DescribeContainer("/tmp/romtest/fine.nsp");
        printf("fine -> %s\n", fine.c_str());
        assert(fine.find("title key") != std::string::npos);

        // Не наш формат — тоже внятный ответ, а не пустая строка.
        const auto other = RomTools::DescribeContainer("/tmp/romtest/padded.xci");
        printf("xci -> %s\n", other.c_str());
        assert(other.find("не NSP") != std::string::npos);
        assert(!RomTools::DescribeContainer("/tmp/romtest/does-not-exist.nsp").empty());
    }

    fs::remove_all("/tmp/romtest");
    printf("\nALL ROM TESTS PASSED\n");
}
