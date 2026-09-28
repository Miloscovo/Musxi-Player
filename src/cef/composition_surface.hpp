#pragma once
#include <windows.h>
#include <d3d11.h>
#include <dcomp.h>
#include <wrl/client.h>
#include <cstdint>
#include <cstring>
#include <vector>

// Premultiplied CEF BGRA is uploaded unchanged. DirectComposition preserves
// per-pixel alpha without making transparent areas pass mouse input through.
class CompositionSurface {
    template<class T> using Ptr=Microsoft::WRL::ComPtr<T>;
public:
    int alphaAt(int x,int y) const {
        if(x<0 || y<0 || x>=width_ || y>=height_ || view_.empty())return -1;
        return view_[(static_cast<size_t>(y)*width_+x)*4+3];
    }
    void clear() {
        if(target_)target_->SetRoot(nullptr);
        if(composition_)composition_->Commit();
        bitmap_.Reset();visual_.Reset();target_.Reset();composition_.Reset();context_.Reset();device_.Reset();
        view_.clear();popup_.clear();combined_.clear();width_=height_=0;
    }
    void popup(bool visible,RECT rect) {popupVisible_=visible;popupRect_=rect;if(!visible)popup_.clear();}
    bool paint(HWND window,bool popup,const void* pixels,int width,int height) {
        if(width<=0 || height<=0 || width>16384 || height>16384)return false;
        const auto size=static_cast<size_t>(width)*height*4;
        if(popup) {popup_.resize(size);std::memcpy(popup_.data(),pixels,size);popupWidth_=width;popupHeight_=height;}
        else {
            if(width!=width_ || height!=height_) {width_=width;height_=height;bitmap_.Reset();popup_.clear();view_.resize(size);}
            std::memcpy(view_.data(),pixels,size);
        }
        return present(window);
    }
    bool present(HWND window) {
        if(view_.empty() || IsIconic(window))return true;
        RECT rect{};GetClientRect(window,&rect);
        if(rect.right!=width_ || rect.bottom!=height_)return true;
        if(!composition_ && !initialize(window))return false;
        if(!bitmap_) {
            if(FAILED(composition_->CreateSurface(width_,height_,DXGI_FORMAT_B8G8R8A8_UNORM,DXGI_ALPHA_MODE_PREMULTIPLIED,&bitmap_)) ||
               FAILED(visual_->SetContent(bitmap_.Get())))return false;
        }
        const std::uint8_t* pixels=view_.data();
        if(popupVisible_ && !popup_.empty()) {
            combined_=view_;
            for(int y=0;y<popupHeight_;++y)for(int x=0;x<popupWidth_;++x) {
                const int dx=popupRect_.left+x,dy=popupRect_.top+y;
                if(dx<0 || dy<0 || dx>=width_ || dy>=height_)continue;
                const auto source=popup_.data()+(static_cast<size_t>(y)*popupWidth_+x)*4;
                auto destination=combined_.data()+(static_cast<size_t>(dy)*width_+dx)*4;
                for(int channel=0;channel<4;++channel)
                    destination[channel]=static_cast<std::uint8_t>(source[channel]+destination[channel]*(255-source[3])/255);
            }
            pixels=combined_.data();
        }
        Ptr<IDXGISurface> drawing;POINT offset{};
        if(FAILED(bitmap_->BeginDraw(nullptr,IID_PPV_ARGS(&drawing),&offset)))return false;
        Ptr<ID3D11Texture2D> texture;
        const auto result=drawing.As(&texture);
        if(SUCCEEDED(result)) {
            const D3D11_BOX box{static_cast<UINT>(offset.x),static_cast<UINT>(offset.y),0,
                static_cast<UINT>(offset.x+width_),static_cast<UINT>(offset.y+height_),1};
            context_->UpdateSubresource(texture.Get(),0,&box,pixels,static_cast<UINT>(width_*4),0);
            context_->Flush();
        }
        const auto ended=bitmap_->EndDraw();
        if(FAILED(result) || FAILED(ended) || FAILED(device_->GetDeviceRemovedReason()))return false;
        return SUCCEEDED(composition_->Commit());
    }
private:
    bool initialize(HWND window) {
        auto result=D3D11CreateDevice(nullptr,D3D_DRIVER_TYPE_HARDWARE,nullptr,D3D11_CREATE_DEVICE_BGRA_SUPPORT,
            nullptr,0,D3D11_SDK_VERSION,&device_,nullptr,&context_);
        if(FAILED(result))result=D3D11CreateDevice(nullptr,D3D_DRIVER_TYPE_WARP,nullptr,D3D11_CREATE_DEVICE_BGRA_SUPPORT,
            nullptr,0,D3D11_SDK_VERSION,&device_,nullptr,&context_);
        Ptr<IDXGIDevice> dxgi;
        if(FAILED(result) || FAILED(device_.As(&dxgi)) ||
            FAILED(DCompositionCreateDevice(dxgi.Get(),IID_PPV_ARGS(&composition_))) ||
            FAILED(composition_->CreateTargetForHwnd(window,TRUE,&target_)) ||
            FAILED(composition_->CreateVisual(&visual_)) || FAILED(target_->SetRoot(visual_.Get()))) {
            clear();return false;
        }
        return true;
    }
    Ptr<ID3D11Device> device_;Ptr<ID3D11DeviceContext> context_;
    Ptr<IDCompositionDevice> composition_;Ptr<IDCompositionTarget> target_;
    Ptr<IDCompositionVisual> visual_;Ptr<IDCompositionSurface> bitmap_;
    int width_=0,height_=0,popupWidth_=0,popupHeight_=0;bool popupVisible_=false;RECT popupRect_{};
    std::vector<std::uint8_t> view_,popup_,combined_;
};
