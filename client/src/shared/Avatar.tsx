export function Avatar({
  role,
  src,
  className = "",
}: {
  role: "user" | "assistant";
  src?: string | null;
  className?: string;
}) {
  const image = role === "assistant" ? "/rei-avatar.png" : src;
  return image ? (
    <img
      className={`avatar ${className}`}
      src={image}
      alt={role === "assistant" ? "れいのアイコン" : "ユーザーのアイコン"}
    />
  ) : (
    <span
      className={`avatar avatar-placeholder ${className}`}
      role="img"
      aria-label="ユーザーのアイコン"
    >
      U
    </span>
  );
}
