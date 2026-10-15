#include "probe.h"

#include <string>

int main() {
    return std::to_string(resource_probe()) == std::string("42") ? 0 : 1;
}
