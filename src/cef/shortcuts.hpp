#pragma once
#include "../../third_party/json.hpp"
#include <array>
#include <functional>
#include <set>
#include <string>
#include <stdexcept>
#include <vector>

namespace musxi::shortcuts {
using Json=nlohmann::json;
inline constexpr std::array<const char*,10> actions{"playPause","previous","next","forward","backward","volumeUp","volumeDown","mute","playbackOrder","visibility"};
struct Binding { unsigned key=0,modifiers=0; };
struct Settings { bool global=false;std::array<Binding,10> bindings{}; };
inline bool validKey(unsigned key) {
    return (key>=0x30 && key<=0x5a && !(key>0x39 && key<0x41)) || (key>=0x60 && key<=0x87)
        || key==0x08 || key==0x09 || key==0x0d || key==0x20 || (key>=0x21 && key<=0x28)
        || key==0x2d || key==0x2e || (key>=0xba && key<=0xc0) || (key>=0xdb && key<=0xde);
}
inline Settings parse(const Json& data) {
    if(!data.is_object() || data.size()!=2 || !data.at("global").is_boolean() || !data.at("bindings").is_object() || data.at("bindings").size()!=actions.size())throw std::runtime_error("无效快捷键配置");
    Settings result;result.global=data.at("global");std::set<std::pair<unsigned,unsigned>> used;
    for(size_t i=0;i<actions.size();++i) {
        const auto& value=data.at("bindings").at(actions[i]);if(value.is_null())continue;
        if(!value.is_object() || value.size()!=2 || !value.at("key").is_number_integer() || !value.at("modifiers").is_number_integer()
            || value.at("key")<0 || value.at("key")>255 || value.at("modifiers")<0 || value.at("modifiers")>15)throw std::runtime_error("无效快捷键");
        Binding b{value.at("key").get<unsigned>(),value.at("modifiers").get<unsigned>()};
        if(!validKey(b.key))throw std::runtime_error("不支持此按键");
        if(!used.emplace(b.key,b.modifiers).second)throw std::runtime_error("此快捷键已绑定其他功能");
        result.bindings[i]=b;
    }
    return result;
}
inline Json json(const Settings& settings) {
    Json bindings=Json::object();for(size_t i=0;i<actions.size();++i) {
        const auto b=settings.bindings[i];bindings[actions[i]]=b.key?Json{{"key",b.key},{"modifiers",b.modifiers}}:Json(nullptr);
    }
    return {{"global",settings.global},{"bindings",bindings}};
}
// Registration and disk writes are injected so failed changes can be tested without stealing real keys.
class Registry {
public:
    Settings settings;std::array<bool,10> registered{},unavailable{};bool suspended=false;
    std::function<bool(int,Binding)> acquire;
    std::function<void(int)> release;
    void clear() {for(int i=0;i<10;++i)if(registered[i]){release(i);registered[i]=false;}}
    void restore() {
        clear();if(!settings.global){unavailable.fill(false);return;}if(suspended)return;
        unavailable.fill(false);
        for(int i=0;i<10;++i)if(settings.bindings[i].key){registered[i]=acquire(i,settings.bindings[i]);unavailable[i]=!registered[i];}
    }
    std::vector<std::string> failed() const {
        std::vector<std::string> result;
        if(settings.global)for(int i=0;i<10;++i)if(unavailable[i])result.push_back(actions[i]);
        return result;
    }
    bool change(const Settings& next,const std::function<bool(const Settings&)>& save) {
        const auto old=settings;const bool paused=suspended;
        suspended=false;settings=next;restore();
        const bool ok=failed().empty() && save(next);
        if(!ok){settings=old;restore();}
        suspended=paused;if(paused)restore();return ok;
    }
};
}
