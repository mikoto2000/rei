import { useState } from "react";

const key = "rei.user-avatar.v1";
const maxBytes = 2 * 1024 * 1024;
const validData = (value: string) =>
  /^data:image\/(png|jpeg|webp);base64,[A-Za-z0-9+/=]+$/.test(value) &&
  value.length <= Math.ceil(maxBytes / 3) * 4 + 64;

export function useUserAvatar() {
  const [avatar, setAvatar] = useState<string | null>(() => {
    try {
      const value = localStorage.getItem(key);
      return value && validData(value) ? value : null;
    } catch {
      return null;
    }
  });
  const save = (value: string | null) => {
    try {
      if (value) localStorage.setItem(key, value);
      else localStorage.removeItem(key);
    } catch {
      throw new Error(
        "アイコンを保存できませんでした。保存領域を確認してください。",
      );
    }
    setAvatar(value);
  };
  return [avatar, save] as const;
}

export async function readUserAvatar(file: File): Promise<string> {
  if (!["image/png", "image/jpeg", "image/webp"].includes(file.type))
    throw new Error("PNG、JPEG、WebP の画像を選択してください。");
  if (file.size > maxBytes)
    throw new Error("2 MB 以下の画像を選択してください。");
  const data = await new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(new Error("画像を読み込めませんでした。"));
    reader.onload = () => resolve(String(reader.result));
    reader.readAsDataURL(file);
  });
  await new Promise<void>((resolve, reject) => {
    const image = new Image();
    image.onload = () => resolve();
    image.onerror = () => reject(new Error("有効な画像を選択してください。"));
    image.src = data;
  });
  return data;
}
