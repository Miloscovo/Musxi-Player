add_library(music_audio_mci STATIC mci_backend.cpp)
musxi_standard(music_audio_mci 17)
target_compile_definitions(music_audio_mci PRIVATE UNICODE _UNICODE NOMINMAX)
target_link_libraries(music_audio_mci PRIVATE winmm ole32 user32)
