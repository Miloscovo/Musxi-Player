#pragma once
#include <windows.h>
#include <d3d11.h>
#include <dcomp.h>
#include <wrl/client.h>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <vector>
#include <algorithm>
#include <cmath>

// Premultiplied CEF BGRA is uploaded unchanged. DirectComposition preserves
// per-pixel alpha without making transparent areas pass mouse input through.
class CompositionSurface {
    template<class T> using Ptr=Microsoft::WRL::ComPtr<T>;
public:
    void setResizing(bool resizing) {resizing_=resizing;}
    void setBottomAligned(bool aligned) {bottomAligned_=aligned;}
    int alphaAt(int x,int y) const {
        y+=viewOffsetY_;
        if((surfaceWidth_!=width_ || surfaceHeight_!=height_) && !combined_.empty()) {
            if(x<0 || y<0 || x>=surfaceWidth_ || y>=surfaceHeight_)return -1;
            return combined_[(static_cast<size_t>(y)*surfaceWidth_+x)*4+3];
        }
        if(x<0 || y<0 || x>=width_ || y>=height_ || view_.empty())return -1;
        return view_[(static_cast<size_t>(y)*width_+x)*4+3];
    }
    void clear() {
        if(target_)target_->SetRoot(nullptr);
        if(composition_)composition_->Commit();
        bitmap_.Reset();visual_.Reset();target_.Reset();composition_.Reset();context_.Reset();device_.Reset();
        view_.clear();popup_.clear();combined_.clear();width_=height_=surfaceWidth_=surfaceHeight_=0;
        bottomAligned_=false;viewOffsetY_=0;contentDirty_=true;
    }
    void popup(bool visible,RECT rect) {popupVisible_=visible;popupRect_=rect;if(!visible)popup_.clear();contentDirty_=true;}
    bool paint(HWND window,bool popup,const void* pixels,int width,int height) {
        if(width<=0 || height<=0 || width>16384 || height>16384)return false;
        contentDirty_=true;
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
        // CEF sizes frames from rounded DIPs, so fractional scales (150%) can
        // differ from the client by a pixel or two. Only skip stale resize frames.
        const int slack=MulDiv(2,GetDpiForWindow(window),96);
        if(!resizing_ && (std::abs(rect.right-width_)>slack || std::abs(rect.bottom-height_)>slack))return true;
        if(!composition_ && !initialize(window))return false;
        const int canvasWidth=bottomAligned_?width_:std::max(1L,rect.right),canvasHeight=bottomAligned_?height_:std::max(1L,rect.bottom);
        viewOffsetY_=bottomAligned_?std::max(0L,height_-rect.bottom):0;
        if(FAILED(visual_->SetOffsetY(static_cast<float>(-viewOffsetY_))))return false;
        if(bottomAligned_ && !contentDirty_ && bitmap_)return SUCCEEDED(composition_->Commit());
        if(canvasWidth!=surfaceWidth_ || canvasHeight!=surfaceHeight_)bitmap_.Reset();
        if(!bitmap_) {
            if(FAILED(composition_->CreateSurface(canvasWidth,canvasHeight,DXGI_FORMAT_B8G8R8A8_UNORM,DXGI_ALPHA_MODE_PREMULTIPLIED,&bitmap_)) ||
               FAILED(visual_->SetContent(bitmap_.Get())))return false;
            surfaceWidth_=canvasWidth;surfaceHeight_=canvasHeight;
        }
        // Clip the frozen viewport at original scale; extend its clear title-bar background, including alpha.
        const std::uint8_t* pixels=view_.data();
        const bool padded=canvasWidth!=width_ || canvasHeight!=height_;
        if(padded) {
            combined_.resize(static_cast<size_t>(canvasWidth)*canvasHeight*4);
            const auto background=view_.data()+(static_cast<size_t>(std::min(height_-1,MulDiv(20,GetDpiForWindow(window),96)))*width_+width_/2)*4;
            const int copyWidth=std::min(width_,canvasWidth),copyHeight=std::min(height_,canvasHeight);
            for(int y=0;y<canvasHeight;++y) {
                auto row=combined_.data()+static_cast<size_t>(y)*canvasWidth*4;
                const int copied=y<copyHeight?copyWidth:0;
                if(copied)std::memcpy(row,view_.data()+static_cast<size_t>(y)*width_*4,static_cast<size_t>(copied)*4);
                for(int x=copied;x<canvasWidth;++x)std::memcpy(row+static_cast<size_t>(x)*4,background,4);
            }
            // Flatten the old CSS corners before rounding the actual window boundary.
            const int radius=std::min({MulDiv(16,GetDpiForWindow(window),96),width_/2,height_/2,canvasWidth/2,canvasHeight/2});
            for(const bool right:{false,true})for(const bool bottom:{false,true})for(int y=0;y<radius;++y) {
                const int sourceY=bottom?height_-1-y:y;
                if(sourceY>=canvasHeight)continue;
                const auto edge=view_.data()+(static_cast<size_t>(bottom?std::max(0,height_-2):std::min(1,height_-1))*width_+(right?width_-radius-1:radius))*4;
                for(int x=0;x<radius;++x) {
                    const int sourceX=right?width_-1-x:x;
                    if(sourceX<canvasWidth && std::hypot(radius-x-.5,radius-y-.5)>radius-.5)
                        std::memcpy(combined_.data()+(static_cast<size_t>(sourceY)*canvasWidth+sourceX)*4,edge,4);
                }
            }
            for(int y=0;y<radius;++y)for(int x=0;x<radius;++x) {
                const double coverage=std::clamp(radius+.5-std::hypot(radius-x-.5,radius-y-.5),0.0,1.0);
                for(const bool right:{false,true})for(const bool bottom:{false,true}) {
                    auto corner=combined_.data()+(static_cast<size_t>(bottom?canvasHeight-1-y:y)*canvasWidth+(right?canvasWidth-1-x:x))*4;
                    for(int channel=0;channel<4;++channel)corner[channel]=static_cast<std::uint8_t>(std::lround(corner[channel]*coverage));
                }
            }
            pixels=combined_.data();
        }
        if(popupVisible_ && !popup_.empty()) {
            if(!padded)combined_=view_;
            for(int y=0;y<popupHeight_;++y)for(int x=0;x<popupWidth_;++x) {
                const int dx=popupRect_.left+x,dy=popupRect_.top+y;
                if(dx<0 || dy<0 || dx>=canvasWidth || dy>=canvasHeight)continue;
                const auto source=popup_.data()+(static_cast<size_t>(y)*popupWidth_+x)*4;
                auto destination=combined_.data()+(static_cast<size_t>(dy)*canvasWidth+dx)*4;
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
                static_cast<UINT>(offset.x+canvasWidth),static_cast<UINT>(offset.y+canvasHeight),1};
            context_->UpdateSubresource(texture.Get(),0,&box,pixels,static_cast<UINT>(canvasWidth*4),0);
            context_->Flush();
        }
        const auto ended=bitmap_->EndDraw();
        if(FAILED(result) || FAILED(ended) || FAILED(device_->GetDeviceRemovedReason()))return false;
        if(FAILED(composition_->Commit()))return false;
        contentDirty_=false;return true;
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
    int surfaceWidth_=0,surfaceHeight_=0;
    bool resizing_=false;
    bool bottomAligned_=false,contentDirty_=true;
    int viewOffsetY_=0;
    std::vector<std::uint8_t> view_,popup_,combined_;
};
