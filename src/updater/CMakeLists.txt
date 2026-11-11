# SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
# SPDX-License-Identifier: GPL-3.0-or-later

add_executable(eden-updater main.cpp ../frontend_common/update_installer.cpp)
target_include_directories(eden-updater PRIVATE ${PROJECT_SOURCE_DIR}/src)
target_compile_features(eden-updater PRIVATE cxx_std_20)
target_link_libraries(eden-updater PRIVATE user32 shell32)
set_target_properties(eden-updater PROPERTIES WIN32_EXECUTABLE ON)

# The helper runs from a temporary folder, away from the DLLs next to eden.exe.
if (MSVC)
    set_property(TARGET eden-updater PROPERTY MSVC_RUNTIME_LIBRARY
                 "MultiThreaded$<$<CONFIG:Debug>:Debug>")
else()
    target_link_options(eden-updater PRIVATE -static)
endif()
