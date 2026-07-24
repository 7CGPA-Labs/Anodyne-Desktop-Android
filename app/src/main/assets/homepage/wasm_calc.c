#include <emscripten.h>

EMSCRIPTEN_KEEPALIVE
int compute_fibonacci(int n) {
    if (n <= 1) {
        return n;
    }
    return compute_fibonacci(n - 1) + compute_fibonacci(n - 2);
}
