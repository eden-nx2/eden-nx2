// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <array>
#include <bit>
#include <chrono>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <span>
#include <string>

#include <catch2/catch_test_macros.hpp>
#include <catch2/generators/catch_generators.hpp>

#include "common/fs/ryujinx_compat.h"
#include "common/fs/symlink.h"

#ifndef _WIN32
#include <unistd.h>
#endif

namespace {

namespace fs = std::filesystem;
using namespace Common::FS;

struct Fixture {
    fs::path root = fs::temp_directory_path() /
                    ("eden-ryujinx-test-" +
                     std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    fs::path eden = root / "eden";
    fs::path ryujinx = root / "ryujinx";

    Fixture() {
        REQUIRE(fs::create_directory(root));
    }
    ~Fixture() {
        std::error_code error;
        fs::remove(eden, error);
        fs::remove(ryujinx, error);
        fs::remove_all(root, error);
    }
};

void WriteSave(const fs::path& path, const std::string& content) {
    fs::create_directories(path);
    std::ofstream save{path / "save.bin", std::ios::binary};
    save << content;
    REQUIRE(save.good());
}

std::string ReadSave(const fs::path& path) {
    std::ifstream save{path / "save.bin", std::ios::binary};
    REQUIRE(save.good());
    return {std::istreambuf_iterator<char>{save}, std::istreambuf_iterator<char>{}};
}

void WriteLe(std::span<char> field, u64 value) {
    for (char& byte : field) {
        byte = static_cast<char>(value & 0xff);
        value >>= 8;
    }
}

struct IndexEntry {
    u64 program;
    u64 id;
    u128 user;
    char type{1};
    char space{1};
    char rank{0};
    char state{0};
};

void WriteIndex(const fs::path& root, std::span<const IndexEntry> entries) {
    const auto path = GetKvdbPath(root);
    fs::create_directories(path.parent_path());
    std::ofstream archive{path, std::ios::binary};
    std::array<char, 12> header{'I', 'M', 'K', 'V'};
    WriteLe(std::span{header}.subspan(8, 4), entries.size());
    archive.write(header.data(), header.size());

    for (const auto& item : entries) {
        std::array<char, 0x8c> entry{'I', 'M', 'E', 'N', 0x40, 0, 0, 0, 0x40};
        WriteLe(std::span{entry}.subspan(0x0c, 8), item.program);
        WriteLe(std::span{entry}.subspan(0x14, 8), item.user[0]);
        WriteLe(std::span{entry}.subspan(0x1c, 8), item.user[1]);
        entry[0x2c] = item.type;
        entry[0x2d] = item.rank;
        WriteLe(std::span{entry}.subspan(0x4c, 8), item.id);
        entry[0x64] = item.space;
        entry[0x65] = item.state;
        archive.write(entry.data(), entry.size());
    }
    REQUIRE(archive.good());
}

} // namespace

TEST_CASE("Ryujinx candidates use title, account, type and committed contents", "[ryujinx]") {
    Fixture f;
    constexpr u64 title = 0x0100f2c0115b6000;
    constexpr std::array<u8, 16> uid_bytes{0x10, 0x32, 0x54, 0x76, 0x98, 0xba, 0xdc, 0xfe,
                                          0xef, 0xcd, 0xab, 0x89, 0x67, 0x45, 0x23, 0x01};
    constexpr u128 eden_uid = std::bit_cast<u128>(uid_bytes);
    constexpr u128 selected{0xfedcba9876543210, 0x0123456789abcdef};
    constexpr u128 recent{0x8899aabbccddeeff, 0x0011223344556677};
    constexpr u128 other{0x0201, 0x0403};
    const std::array entries{
        IndexEntry{title, 0x04, other},          IndexEntry{title, 0x0e, selected},
        IndexEntry{title, 0x0d, selected},       IndexEntry{title, 0x0f, recent},
        IndexEntry{title, 0x11, {}, 3},          IndexEntry{title, 2, selected},
        IndexEntry{title, 3, selected, 2},       IndexEntry{title, 5, selected, 1, 0},
        IndexEntry{title, 6, selected, 1, 1, 1}, IndexEntry{title, 7, selected, 1, 1, 0, 3},
        IndexEntry{title, 8, selected, 3},       IndexEntry{title + 1, 9, selected},
    };
    WriteIndex(f.ryujinx, entries);
    const auto saves_root = f.ryujinx / "bis/user/save";
    for (const auto* id : {"03", "04", "05", "06", "07", "08", "09", "0d", "0e", "0f", "11"}) {
        WriteSave(saves_root / (std::string(14, '0') + id) / "0", id);
    }
    WriteSave(saves_root / "0000000000000002" / "1", "uncommitted");

    std::vector<RyujinxSave> saves;
    REQUIRE(ReadRyujinxSaves(f.ryujinx, title, eden_uid, recent, saves) == IMENReadResult::Success);
    REQUIRE(saves.size() == 5);
    CHECK(saves[0].user_id == selected);
    CHECK(saves[0].type == RyujinxSaveType::Account);
    CHECK(saves[0].save_id == 0x0d);
    CHECK(ReadSave(saves[0].path) == "0d");
    CHECK(saves[1].save_id == 0x0e);
    CHECK(saves[2].user_id == recent);
    CHECK(saves[2].save_id == 0x0f);
    CHECK(saves[3].type == RyujinxSaveType::Device);
    CHECK(saves[3].save_id == 0x11);
    CHECK(saves[4].user_id == other);
    CHECK(saves[4].save_id == 0x04);

    REQUIRE(ReadRyujinxSaves(f.ryujinx, title, recent, selected, saves) == IMENReadResult::Success);
    CHECK(saves[0].save_id == 0x0f);
}

TEST_CASE("Malformed Ryujinx index never exposes a partial result", "[ryujinx]") {
    Fixture f;
    constexpr u64 title = 0x0100f2c0115b6000;
    constexpr u128 user{1, 0};
    const std::array entries{IndexEntry{title, 0x0d, user}, IndexEntry{title, 0x0e, user}};
    WriteIndex(f.ryujinx, entries);
    WriteSave(f.ryujinx / "bis/user/save/000000000000000d/0", "0d");
    std::fstream archive{GetKvdbPath(f.ryujinx), std::ios::binary | std::ios::in | std::ios::out};
    REQUIRE(archive.seekp(12 + 0x8c + 4).put(0x30).good());
    archive.close();

    std::vector<RyujinxSave> saves;
    CHECK(ReadRyujinxSaves(f.ryujinx, title, user, user, saves) == IMENReadResult::Misaligned);
    CHECK(saves.empty());
}

TEST_CASE("A failed Ryujinx link leaves both saves untouched", "[ryujinx]") {
    Fixture f;
    WriteSave(f.eden, "Eden save");
    WriteSave(f.ryujinx, "Ryujinx save");
    const auto fail_link = [](fs::path from, fs::path to) {
        const auto eden = from.parent_path() / "eden";
        CHECK(to != eden);
        CHECK(ReadSave(eden) == "Eden save");
        CHECK(ReadSave(from) == "Ryujinx save");
        return false;
    };

    for (const auto operation :
         {RyujinxSaveOperation::FromRyujinx, RyujinxSaveOperation::FromEden}) {
        const auto result = UpdateRyujinxSave(f.eden, f.ryujinx, operation, fail_link);
        CHECK_FALSE(result.error.empty());
        CHECK(result.backups.empty());
        CHECK(ReadSave(f.eden) == "Eden save");
        CHECK(ReadSave(f.ryujinx) == "Ryujinx save");
        CHECK(std::distance(fs::directory_iterator(f.root), fs::directory_iterator{}) == 2);
    }
}

TEST_CASE("A missing source never replaces the destination with a dangling link", "[ryujinx]") {
    Fixture f;
    WriteSave(f.eden, "Eden save");
    const auto result = UpdateRyujinxSave(f.eden, f.ryujinx, RyujinxSaveOperation::FromRyujinx,
                                          [](fs::path, fs::path) {
                                              FAIL("Link creation ran without a source");
                                              return false;
                                          });
    CHECK_FALSE(result.error.empty());
    CHECK(ReadSave(f.eden) == "Eden save");
    CHECK_FALSE(fs::exists(f.ryujinx));
}

TEST_CASE("A failed Eden copy does not replace the Ryujinx save", "[ryujinx]") {
    Fixture f;
    WriteSave(f.eden, "Eden save");
    WriteSave(f.ryujinx, "Ryujinx save");
    REQUIRE(CreateSymlink(f.ryujinx, f.eden / "link"));
    const auto result = UpdateRyujinxSave(f.eden, f.ryujinx, RyujinxSaveOperation::FromEden);
    CHECK_FALSE(result.error.empty());
    CHECK(result.backups.empty());
    CHECK(ReadSave(f.eden) == "Eden save");
    CHECK(ReadSave(f.ryujinx) == "Ryujinx save");
    CHECK(fs::remove(f.eden / "link"));
}

#ifndef _WIN32
TEST_CASE("A failed Eden publish rolls back the already replaced Ryujinx save", "[ryujinx]") {
    if (geteuid() == 0) {
        SKIP("Directory permissions do not prevent root from renaming");
    }
    Fixture f;
    const auto eden = f.root / "nand" / "eden";
    WriteSave(eden, "Eden save");
    WriteSave(f.ryujinx, "Ryujinx save");
    const auto deny_publish = [](fs::path from, fs::path to) {
        if (!CreateSymlink(from, to)) {
            return false;
        }
        fs::permissions(to.parent_path().parent_path(), fs::perms::owner_write,
                        fs::perm_options::remove);
        return true;
    };
    const auto result =
        UpdateRyujinxSave(eden, f.ryujinx, RyujinxSaveOperation::FromEden, deny_publish);
    fs::permissions(eden.parent_path(), fs::perms::owner_write, fs::perm_options::add);
    CHECK_FALSE(result.error.empty());
    CHECK(result.backups.empty());
    CHECK(ReadSave(eden) == "Eden save");
    CHECK(ReadSave(f.ryujinx) == "Ryujinx save");
}
#endif

TEST_CASE("Ryujinx link and unlink preserve saves and backups", "[ryujinx]") {
    Fixture f;
    WriteSave(f.eden, "Eden save");
    WriteSave(f.ryujinx, "Ryujinx save");
    const auto operation =
        GENERATE(RyujinxSaveOperation::FromRyujinx, RyujinxSaveOperation::FromEden);
    const auto result = UpdateRyujinxSave(f.eden, f.ryujinx, operation);
    INFO(result.error);
    REQUIRE(result.error.empty());
    REQUIRE(result.backups.size() == (operation == RyujinxSaveOperation::FromEden ? 2U : 1U));
    CHECK(ReadSave(result.backups[0]) == "Eden save");
    if (operation == RyujinxSaveOperation::FromEden) {
        CHECK(ReadSave(result.backups[1]) == "Ryujinx save");
    }
    REQUIRE(IsSymlink(f.eden));
    CHECK(fs::equivalent(f.eden, f.ryujinx));

    const auto unlink = UpdateRyujinxSave(f.eden, f.ryujinx, RyujinxSaveOperation::Unlink);
    INFO(unlink.error);
    REQUIRE(unlink.error.empty());
    CHECK_FALSE(IsSymlink(f.eden));
    CHECK_FALSE(fs::equivalent(f.eden, f.ryujinx));
    CHECK(ReadSave(f.eden) ==
          (operation == RyujinxSaveOperation::FromEden ? "Eden save" : "Ryujinx save"));
    CHECK(ReadSave(f.eden) == ReadSave(f.ryujinx));
}
