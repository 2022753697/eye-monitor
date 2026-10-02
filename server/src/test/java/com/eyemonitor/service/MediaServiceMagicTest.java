package com.eyemonitor.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MediaService.magicMatches 单元测试（审查测试缺口补充 2026-10）。
 * jpg/png/webp/mp4 家族/heic/mp3/aac 正反例矩阵 + 小文件放行。
 */
class MediaServiceMagicTest {

    private static byte[] h(String hexHead) {
        // "FFD8FF" → byte[]
        byte[] out = new byte[hexHead.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hexHead.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static byte[] pad(String ascii) {
        byte[] b = ascii.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[16];
        System.arraycopy(b, 0, out, 0, b.length);
        return out;
    }

    @Test
    void jpgMagic() {
        assertTrue(MediaService.magicMatches(h("FFD8FFE000104A464946"), "jpg"));
        assertFalse(MediaService.magicMatches(h("89504E470D0A1A0A"), "jpg")); // png 内容冒充 jpg
    }

    @Test
    void pngMagic() {
        assertTrue(MediaService.magicMatches(h("89504E470D0A1A0A0000000D"), "png"));
        assertFalse(MediaService.magicMatches(h("FFD8FFE000104A464946"), "png"));
    }

    @Test
    void webpMagic() {
        assertTrue(MediaService.magicMatches(h("524946460000000057454250"), "webp")); // RIFF....WEBP
        assertFalse(MediaService.magicMatches(h("FFD8FFE000104A464946"), "webp"));
    }

    @Test
    void mp4FamilyMagic() {
        byte[] ftypMp4 = pad("0000ftypmp42");
        assertTrue(MediaService.magicMatches(ftypMp4, "mp4"));
        assertTrue(MediaService.magicMatches(pad("0000ftypisom"), "mov"));
        assertTrue(MediaService.magicMatches(pad("0000ftyp3gp4"), "3gp"));
        assertTrue(MediaService.magicMatches(pad("0000ftypM4A "), "m4a"));
        // M-6（修复）：heic/heif 同 ftyp 容器校验
        assertTrue(MediaService.magicMatches(pad("0000ftypheic"), "heic"));
        assertTrue(MediaService.magicMatches(pad("0000ftypheix"), "heif"));
        // 伪装：jpg 头冒充 mp4
        assertFalse(MediaService.magicMatches(h("FFD8FFE000104A46494600000000"), "mp4"));
    }

    @Test
    void mp3AndAacMagic() {
        assertTrue(MediaService.magicMatches(h("49443304000000000C0000"), "mp3")); // ID3
        assertFalse(MediaService.magicMatches(h("FFD8FFE000104A464946"), "mp3"));
        assertTrue(MediaService.magicMatches(h("FFF3E0640000000000000000"), "aac")); // 0xFFF3 sync
    }

    @Test
    void smallFileBypass() {
        // <8 字节放行（不误伤小文件，大小受上传上限约束）
        assertTrue(MediaService.magicMatches(new byte[]{0x01, 0x02, 0x03}, "jpg"));
    }
}