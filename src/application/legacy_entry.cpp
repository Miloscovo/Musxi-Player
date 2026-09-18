#include <windows.h>
#include "application.hpp"
int WINAPI wWinMain(HINSTANCE instance, HINSTANCE, PWSTR, int show) {
    return musxi::runNativeApplication(instance, show);
}
