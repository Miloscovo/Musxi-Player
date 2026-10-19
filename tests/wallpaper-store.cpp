#include "../src/cef/wallpaper_store.hpp"
#include <iostream>

void require(bool ok,const char* message){if(!ok)throw std::runtime_error(message);}
int main(int argc,char** argv) try {
    using namespace musxi::wallpaper;namespace fs=std::filesystem;
    require(argc==2,"fixture path");const fs::path fixtures=fs::u8path(argv[1]);
    for(const auto* extension:{"png","jpg","webp"}) {
        const auto image=read(fixtures/(std::string("test.")+extension));
        require(image.width==320 && image.height==180 && !image.mime.empty(),"supported format dimensions");
        const auto copy=displayCopy(image);
        require(copy.width==320 && copy.height==180 && copy.mime=="image/jpeg","opaque input was not prepared without upscaling");
    }
    auto png=read(fixtures/"test.png").bytes;
    const auto largeCopy=displayCopy(read(fixtures/"large.png"));
    require(largeCopy.width==3840 && largeCopy.height==1920,"large image aspect ratio or longest edge changed");
    for(const auto* name:{"alpha.png","alpha.webp"}) {
        const auto alpha=displayCopy(read(fixtures/name));
        require(alpha.width==128 && alpha.height==64 && alpha.mime=="image/png","alpha image was flattened or upscaled");
        require(displayCopy(alpha).mime=="image/png","display copy lost transparency");
    }
    auto portrait=read(fixtures/"test.jpg");
    const unsigned char exif[]{0xff,0xe1,0,34,'E','x','i','f',0,0,'I','I',42,0,8,0,0,0,1,0,0x12,1,3,0,1,0,0,0,6,0,0,0,0,0,0,0};
    portrait.bytes.insert(portrait.bytes.begin()+2,std::begin(exif),std::end(exif));
    const auto rotated=displayCopy(portrait);require(rotated.width==180 && rotated.height==320,"JPEG EXIF orientation ignored");
    bool decodeRejected=false;try{displayCopy(read(fixtures/"invalid.png"));}catch(...){decodeRejected=true;}require(decodeRejected,"invalid pixel stream accepted");
    bool rejected=false;try{inspect(std::vector<unsigned char>(20,'x'));}catch(...){rejected=true;}require(rejected,"invalid image accepted");
    auto bad=png;bad[16]=0x7f;rejected=false;try{inspect(bad);}catch(...){rejected=true;}require(rejected,"huge pixel dimensions accepted");
    bad=png;bad.resize(bad.size()-4);rejected=false;try{inspect(bad);}catch(...){rejected=true;}require(rejected,"truncated PNG accepted");
    bad=png;const unsigned char animation[]{0,0,0,8,'a','c','T','L',0,0,0,1,0,0,0,0,0,0,0,0};bad.insert(bad.begin()+33,std::begin(animation),std::end(animation));
    rejected=false;try{inspect(bad);}catch(...){rejected=true;}require(rejected,"animated PNG accepted");
    const auto root=fs::temp_directory_path()/(L"musxi-wallpaper-"+std::to_wstring(GetCurrentProcessId())+L"-"+std::to_wstring(GetTickCount64()));
    fs::create_directories(root);const auto source=root/L"source.png";
    {
        auto large=png;large.resize(32*1024*1024);
        {std::ofstream file(source,std::ios::binary);file.write(reinterpret_cast<const char*>(large.data()),large.size());}
        require(read(source).bytes.size()==large.size(),"32 MiB wallpaper rejected");
        const auto prepared=displayCopy(read(source));require(prepared.bytes.size()<1024*1024 && prepared.width==320 && prepared.height==180,"large original was not replaced with a small display copy");
        large.push_back(0);rejected=false;try{inspect(large);}catch(...){rejected=true;}require(rejected,"oversized payload accepted");
        {std::ofstream file(source,std::ios::binary|std::ios::app);file.put(0);}
        rejected=false;try{read(source);}catch(...){rejected=true;}require(rejected,"oversized file accepted");
    }
    fs::copy_file(fixtures/"test.png",source,fs::copy_options::overwrite_existing);
    Store store(root/L"managed");require(store.current().id.empty(),"unexpected saved wallpaper");
    const auto first=store.select(source);store.accept(first.id);
    require(!fs::exists(root/L"managed"/L"display.image"),"selection was persisted before apply");
    const auto second=store.select(fixtures/"test.jpg");store.discard(second.id);
    rejected=false;try{store.apply(second.id);}catch(...){rejected=true;}require(rejected,"discarded candidate was applied");
    const auto saved=store.apply(first.id);require(saved.mime=="image/jpeg","invalid choice replaced previous candidate");
    require(read(source).bytes==png,"original image was modified");
    fs::remove(source);Store restarted(root/L"managed");
    require(restarted.current().bytes==saved.bytes,"restart lost or recompressed the display copy");
    {std::ofstream file(root/L"managed"/L"display.image",std::ios::binary);file<<"broken";}
    rejected=false;try{restarted.current();}catch(...){rejected=true;}require(rejected,"corrupt managed copy accepted");
    restarted.reset();require(restarted.current().id.empty(),"reset retained wallpaper");
    fs::copy_file(fixtures/"test.png",root/L"managed"/L"current.image");
    const auto migrated=restarted.current();require(migrated.mime=="image/jpeg" && !fs::exists(root/L"managed"/L"current.image"),"legacy wallpaper was not migrated");
    require(restarted.current().bytes==migrated.bytes,"migrated copy was regenerated");restarted.reset();
    fs::remove(root/L"managed");fs::remove(root);
    std::cout<<"wallpaper formats, bounds, static validation, staging, persistence and reset passed\n";return 0;
}catch(const std::exception& error){std::cerr<<error.what()<<"\n";return 1;}
