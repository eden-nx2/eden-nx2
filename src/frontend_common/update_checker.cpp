// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#include <array>
#include <charconv>
#include "common/logging.h"
#include "common/scm_rev.h"
#include "frontend_common/update_checker.h"

namespace UpdateChecker {

namespace {

// A release's place in the update order: vMAJOR.MINOR.PATCH tags (1.0.0 and later), or the
// build-N tags of earlier releases, which sort below every version as 0.0.N.
using ReleaseVersion = std::array<u64, 3>;

std::optional<u64> ParseNumber(std::string_view text) {
    u64 number{};
    const auto [end, error] = std::from_chars(text.data(), text.data() + text.size(), number);
    if (text.empty() || error != std::errc{} || end != text.data() + text.size()) {
        return std::nullopt;
    }
    return number;
}

std::optional<ReleaseVersion> ParseReleaseVersion(std::string_view tag) {
    constexpr std::string_view build_prefix = "build-";
    if (tag.starts_with(build_prefix)) {
        const auto number = ParseNumber(tag.substr(build_prefix.size()));
        if (!number || *number == 0) {
            return std::nullopt;
        }
        return ReleaseVersion{0, 0, *number};
    }
    if (!tag.starts_with('v')) {
        return std::nullopt;
    }
    tag.remove_prefix(1);
    ReleaseVersion version{};
    for (size_t part = 0; part < version.size(); ++part) {
        const size_t dot = tag.find('.');
        if ((part + 1 < version.size()) == (dot == std::string_view::npos)) {
            return std::nullopt;
        }
        const auto number = ParseNumber(tag.substr(0, dot));
        if (!number) {
            return std::nullopt;
        }
        version[part] = *number;
        tag = dot == std::string_view::npos ? std::string_view{} : tag.substr(dot + 1);
    }
    if (version == ReleaseVersion{}) {
        return std::nullopt;
    }
    return version;
}

} // namespace

Failure ClassifyFailure(int status) {
    if (status == 403 || status == 429) {
        return Failure::RateLimited;
    }
    return status >= 400 ? Failure::Http : Failure::Network;
}

std::optional<Common::Net::Release> SelectUpdate(std::span<const Common::Net::Release> releases,
                                                 std::string_view build_tag,
                                                 std::string_view platform,
                                                 Common::Net::LinuxPackage package) {
    auto newest = ParseReleaseVersion(build_tag);
    if (!newest) {
        return std::nullopt;
    }

    const Common::Net::Release* selected = nullptr;
    for (const auto& release : releases) {
        const auto version = ParseReleaseVersion(release.tag);
        if (version && *version > *newest && !release.GetPlatformAssets(platform, package).empty()) {
            newest = version;
            selected = &release;
        }
    }
    return selected ? std::make_optional(*selected) : std::nullopt;
}

bool IsReleaseBuild(std::string_view build_tag, std::string_view platform) {
    return ParseReleaseVersion(build_tag) && !platform.empty();
}

CheckResult Check() {
    if (!IsReleaseBuild(Common::g_build_release_tag, Common::g_build_release_platform)) {
        return {.failure = Failure::NotReleaseBuild};
    }

    const auto list = Common::Net::GetReleases();
    if (!list.ok) {
        const auto failure = ClassifyFailure(list.status);
        LOG_WARNING(Frontend, "Update check failed with status {}", list.status);
        return {.failure = failure, .status = list.status};
    }

    auto update = SelectUpdate(list.releases, Common::g_build_release_tag,
                               Common::g_build_release_platform,
                               Common::Net::CurrentLinuxPackage());
    if (update) {
        LOG_INFO(Frontend, "Received update {}", update->title);
    }
    return {.update = std::move(update)};
}

std::optional<Common::Net::Release> GetUpdate() {
    return Check().update;
}

} // namespace UpdateChecker
