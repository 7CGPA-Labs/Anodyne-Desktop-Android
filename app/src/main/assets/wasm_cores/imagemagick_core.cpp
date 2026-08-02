#include <iostream>
#include <vector>
#include <string>

#ifdef __EMSCRIPTEN__
#include <emscripten/bind.h>
#endif

// Mock of ImageMagick / Resizing pipeline in C++ for compilation to WASM
class ImageMagickCore {
public:
    ImageMagickCore() {}

    std::string resizeImage(const std::string& inputBuffer, int targetWidth, int targetHeight) {
        std::cout << "Resizing image of length " << inputBuffer.size() 
                  << " to dimensions: " << targetWidth << "x" << targetHeight << std::endl;
        
        // Output a resized dummy output buffer
        std::string mockBuffer(targetWidth * targetHeight * 4, (char)128);
        return mockBuffer;
    }
};

#ifdef __EMSCRIPTEN__
EMSCRIPTEN_BINDINGS(imagemagick_core) {
    emscripten::class_<ImageMagickCore>("ImageMagickCore")
        .constructor()
        .function("resizeImage", &ImageMagickCore::resizeImage);
}
#else
int main() {
    ImageMagickCore im;
    std::cout << "Resized buffer size: " << im.resizeImage("MOCK_IMG_DATA", 100, 100).size() << std::endl;
    return 0;
}
#endif
