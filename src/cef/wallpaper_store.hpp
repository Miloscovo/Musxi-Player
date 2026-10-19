#pragma once
#include <windows.h>
#include <filesystem>
#include <fstream>
#include <string>
#include <vector>
#include <stdexcept>
#include <cstdint>
#include <cwctype>
#include <algorithm>

namespace musxi::wallpaper {
struct Image {
    std::string id,mime;unsigned width=0,height=0;std::vector<unsigned char> bytes;
};
inline Image inspect(std::vector<unsigned char> bytes) {
    if(bytes.size()<12 || bytes.size()>32*1024*1024)throw std::runtime_error("请选择不超过 32 MB 的静态图片");
    Image image;
    const auto be32=[&](size_t p)->uint32_t {if(p+4>bytes.size())throw std::runtime_error("图片内容不完整");return (uint32_t(bytes[p])<<24)|(uint32_t(bytes[p+1])<<16)|(uint32_t(bytes[p+2])<<8)|bytes[p+3];};
    const auto le32=[&](size_t p)->uint32_t {if(p+4>bytes.size())throw std::runtime_error("图片内容不完整");return bytes[p]|(uint32_t(bytes[p+1])<<8)|(uint32_t(bytes[p+2])<<16)|(uint32_t(bytes[p+3])<<24);};
    const auto tag=[&](size_t p,const char* text){return p+4<=bytes.size() && std::equal(bytes.begin()+p,bytes.begin()+p+4,text);};
    if(bytes[0]==137 && tag(1,"PNG\r") && bytes[5]==10 && bytes[6]==26 && bytes[7]==10) {
        if(bytes.size()<33 || be32(8)!=13 || !tag(12,"IHDR"))throw std::runtime_error("无效 PNG 图片");
        image.mime="image/png";image.width=be32(16);image.height=be32(20);bool ended=false;
        for(size_t p=8;p+12<=bytes.size();) {
            const auto length=be32(p);if(length>bytes.size()-p-12)throw std::runtime_error("图片内容不完整");
            if(tag(p+4,"acTL"))throw std::runtime_error("暂不支持动态图片，请选择静态壁纸");
            if(tag(p+4,"IEND")){ended=length==0;break;}p+=12+length;
        }
        if(!ended)throw std::runtime_error("图片内容不完整");
    } else if(bytes[0]==0xff && bytes[1]==0xd8) {
        image.mime="image/jpeg";
        for(size_t p=2;p+4<=bytes.size();) {
            if(bytes[p++]!=0xff)throw std::runtime_error("无效 JPEG 图片");
            while(p<bytes.size() && bytes[p]==0xff)++p;
            if(p>=bytes.size())break;const auto marker=bytes[p++];
            if(marker==0xd9 || marker==0xda)break;
            if(marker==0x01 || (marker>=0xd0 && marker<=0xd7))continue;
            if(p+2>bytes.size())break;
            const size_t length=(unsigned(bytes[p])<<8)|bytes[p+1];if(length<2 || length>bytes.size()-p)throw std::runtime_error("图片内容不完整");
            if(marker>=0xc0 && marker<=0xcf && marker!=0xc4 && marker!=0xc8 && marker!=0xcc) {
                if(length<8)throw std::runtime_error("无效 JPEG 图片");
                image.height=(unsigned(bytes[p+3])<<8)|bytes[p+4];image.width=(unsigned(bytes[p+5])<<8)|bytes[p+6];break;
            }p+=length;
        }
    } else if(tag(0,"RIFF") && tag(8,"WEBP")) {
        if(uint64_t(le32(4))+8!=bytes.size())throw std::runtime_error("无效 WebP 图片");image.mime="image/webp";
        for(size_t p=12;p+8<=bytes.size();) {
            const auto length=le32(p+4);const size_t data=p+8;
            if(length>bytes.size()-data)throw std::runtime_error("图片内容不完整");
            if(tag(p,"ANIM") || tag(p,"ANMF") || (tag(p,"VP8X") && length>=10 && (bytes[data]&2)))throw std::runtime_error("暂不支持动态图片，请选择静态壁纸");
            if(tag(p,"VP8X") && length>=10) {
                image.width=1+bytes[data+4]+(unsigned(bytes[data+5])<<8)+(unsigned(bytes[data+6])<<16);
                image.height=1+bytes[data+7]+(unsigned(bytes[data+8])<<8)+(unsigned(bytes[data+9])<<16);
            } else if(tag(p,"VP8 ") && length>=10 && bytes[data+3]==0x9d && bytes[data+4]==1 && bytes[data+5]==0x2a) {
                image.width=(bytes[data+6]|(unsigned(bytes[data+7])<<8))&0x3fff;image.height=(bytes[data+8]|(unsigned(bytes[data+9])<<8))&0x3fff;
            } else if(tag(p,"VP8L") && length>=5 && bytes[data]==0x2f) {
                const auto bits=le32(data+1);image.width=(bits&0x3fff)+1;image.height=((bits>>14)&0x3fff)+1;
            }p=data+length+(length&1);
        }
    } else throw std::runtime_error("仅支持 JPG、JPEG、PNG 和 WebP 图片");
    if(!image.width || !image.height || image.width>16384 || image.height>16384 || uint64_t(image.width)*image.height>40000000)throw std::runtime_error("图片尺寸无效或过大（最多 4000 万像素）");
    image.bytes=std::move(bytes);return image;
}
inline Image read(const std::filesystem::path& file) {
    const auto size=std::filesystem::file_size(file);if(!size || size>32*1024*1024)throw std::runtime_error("请选择不超过 32 MB 的静态图片");
    std::vector<unsigned char> bytes(static_cast<size_t>(size));std::ifstream stream(file,std::ios::binary);
    stream.read(reinterpret_cast<char*>(bytes.data()),static_cast<std::streamsize>(bytes.size()));
    if(!stream || stream.peek()!=std::char_traits<char>::eof())throw std::runtime_error("无法读取图片，请重新选择");
    return inspect(std::move(bytes));
}
Image displayCopy(Image image);
class Store {
    std::filesystem::path directory_;Image candidate_,incoming_;unsigned nextId_=0;
    void save(const Image& image) const {
        std::filesystem::create_directories(directory_);
        const auto temporary=directory_/L"current.tmp",target=directory_/L"display.image";
        try {
            {std::ofstream file(temporary,std::ios::binary);file.write(reinterpret_cast<const char*>(image.bytes.data()),static_cast<std::streamsize>(image.bytes.size()));file.close();if(!file)throw std::runtime_error("无法保存壁纸");}
            if(!MoveFileExW(temporary.c_str(),target.c_str(),MOVEFILE_REPLACE_EXISTING|MOVEFILE_WRITE_THROUGH))throw std::runtime_error("无法保存壁纸，请检查用户目录权限");
        }catch(...){DeleteFileW(temporary.c_str());throw;}
    }
public:
    explicit Store(std::filesystem::path directory):directory_(std::move(directory)){DeleteFileW((directory_/L"current.tmp").c_str());}
    Image current() const {
        auto file=directory_/L"display.image";
        if(!std::filesystem::exists(file)) {
            file=directory_/L"current.image";if(!std::filesystem::exists(file))return {};
            auto image=displayCopy(read(file));save(image);DeleteFileW(file.c_str());
            image.id="saved";return image;
        }
        auto image=read(file);image.id="saved";return image;
    }
    const Image& select(const std::filesystem::path& file) {
        auto extension=file.extension().wstring();for(auto& c:extension)c=static_cast<wchar_t>(std::towlower(c));
        if(extension!=L".jpg" && extension!=L".jpeg" && extension!=L".png" && extension!=L".webp")throw std::runtime_error("仅支持 JPG、JPEG、PNG 和 WebP 图片");
        auto image=displayCopy(read(file));image.id="candidate-"+std::to_string(++nextId_);incoming_=std::move(image);return incoming_;
    }
    void accept(const std::string& id) {
        if(id.empty() || incoming_.id!=id)throw std::runtime_error("候选图片已变化，请重新选择");
        candidate_=std::move(incoming_);incoming_={};
    }
    void discard(const std::string& id) {if(incoming_.id==id)incoming_={};}
    Image apply(const std::string& id) {
        if(id=="saved")return current();
        if(id.empty() || candidate_.id!=id)throw std::runtime_error("候选图片已变化，请重新选择");
        save(candidate_);DeleteFileW((directory_/L"current.image").c_str());
        auto image=std::move(candidate_);candidate_={};image.id="saved";return image;
    }
    void reset() {
        for(const auto* name:{L"current.image",L"display.image"})
            if(!DeleteFileW((directory_/name).c_str()) && GetLastError()!=ERROR_FILE_NOT_FOUND && GetLastError()!=ERROR_PATH_NOT_FOUND)throw std::runtime_error("无法移除已保存的壁纸");
        candidate_={};incoming_={};
    }
};
}
