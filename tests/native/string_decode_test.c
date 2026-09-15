#include "nseel-compiler.c"

int main(void)
{
    compileContext *ctx = calloc(1, sizeof(*ctx));
    assert(ctx);
    void *strings[] = { s_str_create_from_c_str("YWJj") };
    eel_builtin_memRegion region = {0};
    region.memRegion = strings;
    region.slot = 1;
    ctx->region_context = &region;
    float id = 0, length = 4;
    assert(_eel_base64_decode(ctx, &id, &id, &length) == 3);
    assert(strcmp(s_str_c_str(&strings[0]), "abc") == 0);
    length = 3;
    assert(_eel_base64_encode(ctx, &id, &id, &length) > 0);
    assert(strncmp(s_str_c_str(&strings[0]), "YWJj", 4) == 0);
    s_str_destroy(&strings[0]);
    free(ctx);
    puts("In-place Base64 encode/decode passed");
    return 0;
}
