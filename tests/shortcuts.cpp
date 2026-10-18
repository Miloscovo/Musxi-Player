#include "../src/cef/shortcuts.hpp"
#include <windows.h>
#include <iostream>

void require(bool ok,const char* message) {if(!ok)throw std::runtime_error(message);}
int main() try {
    using namespace musxi::shortcuts;
    const auto blank=json(Settings{});auto defaults=parse(blank);
    require(!defaults.global,"global default");for(const auto& b:defaults.bindings)require(!b.key,"default binding");
    auto invalid=blank;invalid["bindings"]["mute"]={{"key",16},{"modifiers",0}};
    bool rejected=false;try{parse(invalid);}catch(...){rejected=true;}require(rejected,"modifier accepted as primary key");
    invalid=blank;invalid["bindings"]["mute"]={{"key",65},{"modifiers",2}};invalid["bindings"]["next"]=invalid["bindings"]["mute"];
    rejected=false;try{parse(invalid);}catch(...){rejected=true;}require(rejected,"duplicate accepted");
    invalid=blank;invalid["bindings"]["mute"]={{"key",65},{"modifiers",-1}};
    rejected=false;try{parse(invalid);}catch(...){rejected=true;}require(rejected,"bad modifiers accepted");
    Registry registry;std::set<int> held;unsigned unavailable=0;int writes=0;
    registry.acquire=[&](int id,Binding b){if(b.key==unavailable)return false;require(held.insert(id).second,"double registration");return true;};
    registry.release=[&](int id){require(held.erase(id)==1,"unregister missing id");};
    const auto save=[&](const Settings&){++writes;return true;};
    auto next=defaults;next.global=true;next.bindings[0]={65,2};next.bindings[1]={66,2};
    const auto restored=parse(Json::parse(json(next).dump()));
    require(restored.global && restored.bindings[0].key==65 && restored.bindings[1].modifiers==2 && !restored.bindings[2].key,"saved settings round trip");
    require(registry.change(next,save) && held.size()==2 && writes==1,"initial registration");
    auto failed=next;failed.bindings[0]={67,2};unavailable=67;
    require(!registry.change(failed,save) && writes==1 && held.size()==2 && registry.settings.bindings[0].key==65,"failed change did not rollback");
    require(!registry.change(defaults,[](const Settings&){return false;}) && held.size()==2,"disk failure did not rollback");
    registry.suspended=true;registry.restore();require(held.empty(),"recording retained keys");
    require(!registry.change(failed,save) && writes==1 && held.empty(),"recording bypassed registration check");
    registry.suspended=false;unavailable=66;registry.restore();
    require(held.size()==1 && registry.failed()==std::vector<std::string>{"previous"},"partial startup registration");
    registry.suspended=true;registry.restore();require(registry.failed()==std::vector<std::string>{"previous"},"suspension hid registration errors");
    require(registry.change(defaults,save) && held.empty(),"defaults did not clear keys");
    // Exercise the real Windows registration lifetime without sending keys to other applications.
    HWND first=CreateWindowW(L"STATIC",L"",0,0,0,0,0,HWND_MESSAGE,nullptr,nullptr,nullptr);
    HWND second=CreateWindowW(L"STATIC",L"",0,0,0,0,0,HWND_MESSAGE,nullptr,nullptr,nullptr);
    require(first && second,"message windows");
    unsigned key=0;for(unsigned candidate=VK_F13;candidate<=VK_F24;++candidate)if(RegisterHotKey(first,1,MOD_CONTROL|MOD_ALT|MOD_SHIFT|MOD_NOREPEAT,candidate)){key=candidate;break;}
    require(key!=0,"no free test combination");
    require(!RegisterHotKey(second,1,MOD_CONTROL|MOD_ALT|MOD_SHIFT|MOD_NOREPEAT,key),"OS duplicate registration succeeded");
    require(UnregisterHotKey(first,1)!=FALSE,"OS unregister failed");
    require(RegisterHotKey(second,1,MOD_CONTROL|MOD_ALT|MOD_SHIFT|MOD_NOREPEAT,key)!=FALSE,"OS key not released");
    UnregisterHotKey(second,1);DestroyWindow(first);DestroyWindow(second);
    std::cout<<"shortcut validation, rollback, suspension, reset and Windows registration passed\n";return 0;
}catch(const std::exception& e){std::cerr<<e.what()<<"\n";return 1;}
