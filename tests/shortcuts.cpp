#include "../src/cef/shortcuts.hpp"
#include <windows.h>
#include <iostream>

void require(bool ok,const char* message) {if(!ok)throw std::runtime_error(message);}
int main() try {
    using namespace musxi::shortcuts;
    PressedKeys keys;
    require(keys.update(76,true) && !keys.update(76,true),"held key repeated");
    require(!keys.update(76,false) && keys.update(76,true),"released key did not rearm");
    keys.update(VK_LCONTROL,true);keys.update(VK_RMENU,true);keys.update(VK_RSHIFT,true);keys.update(VK_RWIN,true);
    require(keys.modifiers()==15,"left/right modifier combination lost");
    keys.update(VK_LCONTROL,false);keys.update(VK_RCONTROL,true);keys.update(VK_LMENU,true);keys.update(VK_RMENU,false);
    require(keys.modifiers()==15,"opposite modifier release cleared held modifier");
    keys.update(VK_RCONTROL,false);require(keys.modifiers()==13,"extra modifier not distinguished");
    keys.down[75]=true;require(!keys.update(75,true),"reinstalled listener retriggered held key");
    require(!keys.update(256,true),"invalid key accepted");
    const auto blank=json(Settings{});auto defaults=parse(blank);
    require(!defaults.global,"global default");for(const auto& b:defaults.bindings)require(!b.key,"default binding");
    auto legacy=blank;legacy["bindings"]["playPause"]={{"key",32},{"modifiers",0}};
    for(size_t i=10;i<actions.size();++i)legacy["bindings"].erase(actions[i]);
    const auto migrated=parse(legacy);
    require(migrated.bindings[0].key==32 && !migrated.bindings[12].key,"legacy shortcut bindings lost");
    require(actions.size()==13,"removed screenshot action remains registered");
    auto retired=blank;retired["global"]=true;
    retired["bindings"]["playPause"]={{"key",32},{"modifiers",0}};
    retired["bindings"]["overlayPlayer"]={{"key",74},{"modifiers",0}};
    retired["bindings"]["overlayScreenshot"]=retired["bindings"]["playPause"];
    const auto migratedRetired=parse(retired);
    require(migratedRetired.global && migratedRetired.bindings[0].key==32 && migratedRetired.bindings[12].key==74,"retired screenshot discarded other bindings");
    require(!json(migratedRetired)["bindings"].contains("overlayScreenshot"),"retired screenshot was serialized");
    auto malformed=retired;malformed["bindings"].erase("overlayPlayer");
    bool malformedRejected=false;try{parse(malformed);}catch(...){malformedRejected=true;}require(malformedRejected,"non-prefix legacy actions accepted");
    auto unknown=legacy;unknown["bindings"].erase("mute");unknown["bindings"]["unknown"]=nullptr;
    bool unknownRejected=false;try{parse(unknown);}catch(...){unknownRejected=true;}require(unknownRejected,"unknown action accepted");
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
    auto overlay=defaults;overlay.global=true;overlay.bindings[0]={65,2};overlay.bindings[12]={66,2};overlay.bindings[11]={67,2};
    registry.suspended=false;unavailable=0;require(registry.change(overlay,save),"disabled overlay settings save");
    require(held==std::set<int>{0} && registry.settings.bindings[12].key==66,"disabled module registered keys or lost binding");
    registry.overlayEnabled=true;registry.restore();require(held==std::set<int>({0,11,12}),"enabling module did not restore keys");
    registry.suspended=true;registry.restore();
    require(held==std::set<int>({11,12}),"search field disabled game shortcut or retained playback shortcut");
    registry.recording=true;registry.restore();require(held.empty(),"recording retained game shortcut");
    registry.recording=false;registry.suspended=false;registry.restore();
    require(held==std::set<int>({0,11,12}),"recording did not restore all shortcuts");
    registry.overlayEnabled=false;registry.restore();
    require(held==std::set<int>{0} && registry.settings.bindings[12].key==66,"disabling module failed to release keys or preserve binding");
    registry.overlayEnabled=true;unavailable=66;registry.restore();
    require(registry.failed()==std::vector<std::string>{"overlayPlayer"},"reenabled occupied key not reported");
    registry.overlayEnabled=false;registry.restore();require(registry.failed().empty(),"disabled module retained unavailable shortcut warning");
    registry.clear();
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
