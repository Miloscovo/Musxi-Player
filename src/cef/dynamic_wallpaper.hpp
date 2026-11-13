#pragma once
#include <filesystem>
#include "../application/application.hpp"
namespace musxi::cef_adapter {
inline constexpr char WallpaperOrigin[]="https://wallpaper.musxi.invalid";
bool wallpaperFrameUrl(const std::string& url);
void initDynamicWallpaper(const std::filesystem::path& profile,const std::filesystem::path& assets,bool smoke=false);
musxi::LibraryReply dynamicWallpaperCommand(const std::string& command,const std::string& params,void* owner);
}
