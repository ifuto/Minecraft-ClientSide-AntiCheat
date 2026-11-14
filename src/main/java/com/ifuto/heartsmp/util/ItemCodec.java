package com.ifuto.heartsmp.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.bukkit.inventory.ItemStack;

/**
 * ItemStack を「超圧縮」して 1 行の文字列にするコーデック。
 * Bukkit の byte シリアライザ → gzip → Base64 の順で変換します。
 */
public final class ItemCodec {

    private ItemCodec() {
    }

    public static String encode(ItemStack item) {
        if (item == null) {
            return "";
        }
        byte[] raw = item.serializeAsBytes();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length);
             GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(raw);
            gzip.finish();
            return "gz:" + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException ex) {
            return "raw:" + Base64.getEncoder().encodeToString(raw);
        }
    }

    public static ItemStack decode(String data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        try {
            if (data.startsWith("gz:")) {
                byte[] compressed = Base64.getDecoder().decode(data.substring(3));
                try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed));
                     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = gzip.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                    return ItemStack.deserializeBytes(out.toByteArray());
                }
            }
            if (data.startsWith("raw:")) {
                return ItemStack.deserializeBytes(Base64.getDecoder().decode(data.substring(4)));
            }
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(data));
        } catch (RuntimeException | IOException ex) {
            return null;
        }
    }
}
