std::unique_ptr<musxi::wallpaper::Store> wallpaperStore;
bool wallpaperChoosing=false;
std::string wallpaperTestChoice;
void initWallpaper(const std::filesystem::path& profile) {
    wallpaperStore=std::make_unique<musxi::wallpaper::Store>(profile/(smoke?L"wallpaper-smoke":L"wallpaper"));
}
nlohmann::json wallpaperImage(const musxi::wallpaper::Image& image) {
    if(image.id.empty())return nullptr;
    return {{"id",image.id},{"url","data:"+image.mime+";base64,"+CefBase64Encode(image.bytes.data(),image.bytes.size()).ToString()},
        {"width",image.width},{"height",image.height}};
}
nlohmann::json chooseWallpaper() {
    IFileOpenDialog* dialog=nullptr;
    auto result=CoCreateInstance(CLSID_FileOpenDialog,nullptr,CLSCTX_INPROC_SERVER,IID_PPV_ARGS(&dialog));
    std::filesystem::path selected;
    if(SUCCEEDED(result)) {
        FILEOPENDIALOGOPTIONS options{};result=dialog->GetOptions(&options);
        if(SUCCEEDED(result))result=dialog->SetOptions(options|FOS_FORCEFILESYSTEM|FOS_FILEMUSTEXIST|FOS_PATHMUSTEXIST|FOS_NOCHANGEDIR);
        const COMDLG_FILTERSPEC filter[]{ {L"静态图片（JPG、PNG、WebP）",L"*.jpg;*.jpeg;*.png;*.webp"} };
        if(SUCCEEDED(result))result=dialog->SetFileTypes(1,filter);
        if(SUCCEEDED(result))result=dialog->SetTitle(L"选择壁纸图片");
        if(SUCCEEDED(result))result=dialog->Show(testWindow?testWindow:nativeWindow);
        if(SUCCEEDED(result)) {
            IShellItem* item=nullptr;result=dialog->GetResult(&item);
            if(SUCCEEDED(result)) {
                PWSTR path=nullptr;result=item->GetDisplayName(SIGDN_FILESYSPATH,&path);
                if(SUCCEEDED(result))selected=path;
                CoTaskMemFree(path);item->Release();
            }
        }
        dialog->Release();
    }
    if(result==HRESULT_FROM_WIN32(ERROR_CANCELLED))return nullptr;
    if(FAILED(result))throw std::runtime_error("无法打开图片，请检查文件访问权限后重试");
    return wallpaperImage(wallpaperStore->select(selected));
}
