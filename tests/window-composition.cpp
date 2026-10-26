#include "../src/cef/composition_surface.hpp"
#include <stdexcept>

int main() {
    const auto check=[](bool value){if(!value)throw std::runtime_error("Window composition regression");};
    const auto instance=GetModuleHandleW(nullptr);
    WNDCLASSW type{};type.lpfnWndProc=DefWindowProcW;type.hInstance=instance;type.lpszClassName=L"MusxiCompositionCheck";
    check(RegisterClassW(&type)!=0);
    const auto window=CreateWindowExW(WS_EX_NOREDIRECTIONBITMAP,type.lpszClassName,L"",WS_POPUP,0,0,640,480,nullptr,nullptr,instance,nullptr);
    check(window!=nullptr);
    {
        CompositionSurface surface;
        std::vector<std::uint32_t> pixels(640*480);
        for(int y=0;y<480;++y)std::fill_n(pixels.data()+y*640,640,static_cast<std::uint32_t>(64+y%128)<<24);
        check(surface.paint(window,false,pixels.data(),640,480));
        surface.setResizing(true);surface.setBottomAligned(true);
        for(const int height:{400,250,120,250,400,480}) {
            check(SetWindowPos(window,nullptr,0,0,640,height,SWP_NOMOVE|SWP_NOZORDER|SWP_NOACTIVATE)!=FALSE);
            check(surface.present(window));
            check(surface.alphaAt(100,height-20)==64+460%128);
            check(surface.alphaAt(100,0)==64+(480-height)%128);
        }
        check(SetWindowPos(window,nullptr,0,0,640,120,SWP_NOMOVE|SWP_NOZORDER|SWP_NOACTIVATE)!=FALSE);
        std::fill(pixels.begin(),pixels.end(),0xb4000000u);
        // Prepare a fresh expanded frame while the physical window is still compact.
        check(surface.paint(window,false,pixels.data(),640,480));
        check(surface.alphaAt(100,100)==180);
        for(const int height:{250,400,480}) {
            check(SetWindowPos(window,nullptr,0,0,640,height,SWP_NOMOVE|SWP_NOZORDER|SWP_NOACTIVATE)!=FALSE);
            check(surface.present(window));check(surface.alphaAt(100,height-20)==180);
        }
        surface.setBottomAligned(false);surface.setResizing(false);
        check(surface.present(window));
        check(surface.alphaAt(100,100)==180);
    }
    DestroyWindow(window);
    UnregisterClassW(type.lpszClassName,instance);
}
