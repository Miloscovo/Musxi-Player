#include "wallpaper_store.hpp"
#include <wincodec.h>
#include <wrl/client.h>
#include <webp/decode.h>
#include <cmath>

namespace musxi::wallpaper {
namespace {
using Microsoft::WRL::ComPtr;
void check(HRESULT result) { if(FAILED(result))throw std::runtime_error("图片无法处理，请选择有效的静态图片"); }
struct Apartment {
    HRESULT result=CoInitializeEx(nullptr,COINIT_APARTMENTTHREADED);
    Apartment(){if(result!=RPC_E_CHANGED_MODE)check(result);}
    ~Apartment(){if(SUCCEEDED(result))CoUninitialize();}
};
ComPtr<IWICBitmapSource> oriented(IWICImagingFactory* factory,IWICBitmapFrameDecode* frame) {
    ComPtr<IWICBitmapSource> source=frame;
    ComPtr<IWICMetadataQueryReader> metadata;
    PROPVARIANT value{};unsigned orientation=1;
    if(SUCCEEDED(frame->GetMetadataQueryReader(&metadata)) && SUCCEEDED(metadata->GetMetadataByName(L"/app1/ifd/{ushort=274}",&value)) && value.vt==VT_UI2)
        orientation=value.uiVal;
    PropVariantClear(&value);
    const WICBitmapTransformOptions transforms[]{WICBitmapTransformRotate0,WICBitmapTransformRotate0,WICBitmapTransformFlipHorizontal,
        WICBitmapTransformRotate180,WICBitmapTransformFlipVertical,
        static_cast<WICBitmapTransformOptions>(WICBitmapTransformRotate90|WICBitmapTransformFlipHorizontal),WICBitmapTransformRotate90,
        static_cast<WICBitmapTransformOptions>(WICBitmapTransformRotate270|WICBitmapTransformFlipHorizontal),WICBitmapTransformRotate270};
    if(orientation>=2 && orientation<=8) {
        ComPtr<IWICBitmapFlipRotator> rotated;check(factory->CreateBitmapFlipRotator(&rotated));
        check(rotated->Initialize(source.Get(),transforms[orientation]));source=rotated;
    }
    return source;
}
}
Image displayCopy(Image image) {
    Apartment apartment;
    ComPtr<IWICImagingFactory> factory;
    check(CoCreateInstance(CLSID_WICImagingFactory,nullptr,CLSCTX_INPROC_SERVER,IID_PPV_ARGS(&factory)));
    ComPtr<IWICBitmapSource> source;
    ComPtr<IWICStream> input;ComPtr<IWICBitmapDecoder> decoder;ComPtr<IWICBitmapFrameDecode> frame;
    std::vector<unsigned char> webpPixels;
    if(image.mime=="image/webp") {
        WebPBitstreamFeatures features{};
        if(WebPGetFeatures(image.bytes.data(),image.bytes.size(),&features)!=VP8_STATUS_OK || features.has_animation
            || features.width!=static_cast<int>(image.width) || features.height!=static_cast<int>(image.height))
            throw std::runtime_error("无效的静态 WebP 图片");
        webpPixels.resize(static_cast<size_t>(image.width)*image.height*4);
        if(!WebPDecodeBGRAInto(image.bytes.data(),image.bytes.size(),webpPixels.data(),webpPixels.size(),image.width*4))
            throw std::runtime_error("WebP 图片无法解码");
        ComPtr<IWICBitmap> bitmap;
        check(factory->CreateBitmapFromMemory(image.width,image.height,GUID_WICPixelFormat32bppBGRA,image.width*4,
            static_cast<UINT>(webpPixels.size()),webpPixels.data(),&bitmap));source=bitmap;
    } else {
        check(factory->CreateStream(&input));check(input->InitializeFromMemory(image.bytes.data(),static_cast<DWORD>(image.bytes.size())));
        check(factory->CreateDecoderFromStream(input.Get(),nullptr,WICDecodeMetadataCacheOnDemand,&decoder));
        check(decoder->GetFrame(0,&frame));source=oriented(factory.Get(),frame.Get());
    }
    UINT width=0,height=0;check(source->GetSize(&width,&height));
    if(!width || !height || width>16384 || height>16384 || uint64_t(width)*height>40000000)throw std::runtime_error("图片尺寸无效或过大");
    const auto edge=std::max(width,height);
    if(edge>3840) {
        const auto scaledWidth=std::max(1u,static_cast<UINT>(std::lround(double(width)*3840/edge)));
        const auto scaledHeight=std::max(1u,static_cast<UINT>(std::lround(double(height)*3840/edge)));
        ComPtr<IWICBitmapScaler> scaler;check(factory->CreateBitmapScaler(&scaler));
        check(scaler->Initialize(source.Get(),scaledWidth,scaledHeight,WICBitmapInterpolationModeFant));
        source=scaler;width=scaledWidth;height=scaledHeight;
    }
    ComPtr<IWICFormatConverter> converter;check(factory->CreateFormatConverter(&converter));
    check(converter->Initialize(source.Get(),GUID_WICPixelFormat32bppBGRA,WICBitmapDitherTypeNone,nullptr,0,WICBitmapPaletteTypeCustom));
    std::vector<unsigned char> pixels(static_cast<size_t>(width)*height*4);
    check(converter->CopyPixels(nullptr,width*4,static_cast<UINT>(pixels.size()),pixels.data()));
    bool alpha=false;for(size_t p=3;p<pixels.size();p+=4)if(pixels[p]!=255){alpha=true;break;}
    ComPtr<IWICBitmap> bitmap;check(factory->CreateBitmapFromMemory(width,height,GUID_WICPixelFormat32bppBGRA,width*4,
        static_cast<UINT>(pixels.size()),pixels.data(),&bitmap));
    ComPtr<IStream> output;check(CreateStreamOnHGlobal(nullptr,TRUE,&output));
    ComPtr<IWICBitmapEncoder> encoder;check(factory->CreateEncoder(alpha?GUID_ContainerFormatPng:GUID_ContainerFormatJpeg,nullptr,&encoder));
    check(encoder->Initialize(output.Get(),WICBitmapEncoderNoCache));
    ComPtr<IWICBitmapFrameEncode> encoded;ComPtr<IPropertyBag2> options;
    check(encoder->CreateNewFrame(&encoded,&options));
    if(!alpha) {
        PROPBAG2 property{};property.pstrName=const_cast<wchar_t*>(L"ImageQuality");VARIANT quality{};quality.vt=VT_R4;quality.fltVal=.92f;
        check(options->Write(1,&property,&quality));
    }
    check(encoded->Initialize(options.Get()));check(encoded->SetSize(width,height));
    WICPixelFormatGUID format=alpha?GUID_WICPixelFormat32bppBGRA:GUID_WICPixelFormat24bppBGR;
    check(encoded->SetPixelFormat(&format));
    ComPtr<IWICFormatConverter> outputPixels;check(factory->CreateFormatConverter(&outputPixels));
    check(outputPixels->Initialize(bitmap.Get(),format,WICBitmapDitherTypeNone,nullptr,0,WICBitmapPaletteTypeCustom));
    check(encoded->WriteSource(outputPixels.Get(),nullptr));check(encoded->Commit());check(encoder->Commit());
    STATSTG stat{};check(output->Stat(&stat,STATFLAG_NONAME));
    if(!stat.cbSize.QuadPart || stat.cbSize.QuadPart>32*1024*1024)throw std::runtime_error("壁纸副本过大，请选择其他图片");
    std::vector<unsigned char> bytes(static_cast<size_t>(stat.cbSize.QuadPart));
    LARGE_INTEGER beginning{};check(output->Seek(beginning,STREAM_SEEK_SET,nullptr));ULONG copied=0;
    check(output->Read(bytes.data(),static_cast<ULONG>(bytes.size()),&copied));
    if(copied!=bytes.size())throw std::runtime_error("无法生成壁纸副本");
    return inspect(std::move(bytes));
}
}
