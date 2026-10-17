# Development builds cannot offer an older published binary as an upgrade.
if(CMAKE_SCRIPT_MODE_FILE)
  set(PROJECT_SOURCE_DIR "${UPDATE_SOURCE}")
  set(PROJECT_BINARY_DIR "${UPDATE_BINARY}")
  set(PROJECT_VERSION "${UPDATE_VERSION}")
endif()
option(MUSXI_RELEASE_BUILD "Build the version-update client as a tagged release" OFF)
find_package(Git QUIET)
set(MUSXI_UPDATE_COMMIT "unknown")
if(GIT_FOUND)
  execute_process(COMMAND "${GIT_EXECUTABLE}" rev-parse --show-toplevel WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}"
    OUTPUT_VARIABLE update_git_root OUTPUT_STRIP_TRAILING_WHITESPACE ERROR_QUIET)
  file(REAL_PATH "${PROJECT_SOURCE_DIR}" update_source_root)
  if(update_git_root)
    file(REAL_PATH "${update_git_root}" update_git_root)
  endif()
  if(update_git_root STREQUAL update_source_root)
    execute_process(COMMAND "${GIT_EXECUTABLE}" rev-parse HEAD WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}"
      OUTPUT_VARIABLE update_commit OUTPUT_STRIP_TRAILING_WHITESPACE ERROR_QUIET)
    if(update_commit MATCHES "^[0-9a-f]+$")
      set(MUSXI_UPDATE_COMMIT "${update_commit}")
    endif()
  endif()
endif()
set(MUSXI_UPDATE_RELEASE false)
if(MUSXI_RELEASE_BUILD)
  if(NOT GIT_FOUND OR MUSXI_UPDATE_COMMIT STREQUAL "unknown")
    message(FATAL_ERROR "Release build requires Git and a fixed source revision")
  endif()
  execute_process(COMMAND "${GIT_EXECUTABLE}" status --porcelain WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}"
    OUTPUT_VARIABLE update_status RESULT_VARIABLE update_status_result)
  execute_process(COMMAND "${GIT_EXECUTABLE}" rev-parse "refs/tags/v${PROJECT_VERSION}^{commit}"
    WORKING_DIRECTORY "${PROJECT_SOURCE_DIR}" OUTPUT_VARIABLE update_tag
    OUTPUT_STRIP_TRAILING_WHITESPACE RESULT_VARIABLE update_tag_result ERROR_QUIET)
  if(NOT update_status_result EQUAL 0 OR NOT update_status STREQUAL "" OR
      NOT update_tag_result EQUAL 0 OR NOT update_tag STREQUAL MUSXI_UPDATE_COMMIT)
    message(FATAL_ERROR "Release build requires clean sources at tag v${PROJECT_VERSION}; use MUSXI_RELEASE_BUILD=OFF for development")
  endif()
  file(READ "${PROJECT_SOURCE_DIR}/frontend/package.json" update_frontend)
  string(JSON update_frontend_version GET "${update_frontend}" version)
  file(READ "${PROJECT_SOURCE_DIR}/installer/MusxiPlayerUpgrade.iss" update_installer)
  string(REGEX MATCH "#define AppVersion \"([^\"]+)\"" unused "${update_installer}")
  if(NOT update_frontend_version STREQUAL PROJECT_VERSION OR NOT CMAKE_MATCH_1 STREQUAL PROJECT_VERSION)
    message(FATAL_ERROR "CMake, frontend and installer release versions must agree")
  endif()
  set(MUSXI_UPDATE_RELEASE true)
endif()
configure_file("${PROJECT_SOURCE_DIR}/src/updates/build_info.hpp.in"
  "${PROJECT_BINARY_DIR}/generated/update_build_info.hpp" @ONLY)
if(NOT CMAKE_SCRIPT_MODE_FILE)
  # Revalidate at build time as well: tags, commits and dirty files can change
  # without CMake's normal configure dependencies changing.
  add_custom_target(musxi_update_identity
    COMMAND "${CMAKE_COMMAND}" "-DUPDATE_SOURCE=${PROJECT_SOURCE_DIR}"
      "-DUPDATE_BINARY=${PROJECT_BINARY_DIR}" "-DUPDATE_VERSION=${PROJECT_VERSION}"
      "-DMUSXI_RELEASE_BUILD=${MUSXI_RELEASE_BUILD}"
      -P "${PROJECT_SOURCE_DIR}/cmake/UpdateBuildInfo.cmake" VERBATIM)
endif()
