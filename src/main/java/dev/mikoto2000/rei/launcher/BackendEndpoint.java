package dev.mikoto2000.rei.launcher;

import java.net.URI;
import java.util.UUID;

/**
 * 起動環境の準備で受け渡すバックエンド接続先を表す。
 *
 * <p>Public discovery metadata. Never contains credentials.
 *
 * @param schemaVersion データ形式形式のバージョン
 * @param instanceId 起動単位の識別子識別子
 * @param storageId 保存識別子
 * @param pid プロセス識別子
 * @param baseUrl 接続先の基準URL
 * @param apiProtocolVersion API通信規約形式のバージョン
 * @param status 状態
 */
public record BackendEndpoint(
    int schemaVersion,
    String instanceId,
    String storageId,
    long pid,
    String baseUrl,
    int apiProtocolVersion,
    String status) {
  /** 現在API通信規約。 */
  public static final int CURRENT_API_PROTOCOL = 2;

  /** 受け取った情報からBackendEndpointを作成する。 */
  public BackendEndpoint {
    if (schemaVersion != 1 || pid <= 0 || apiProtocolVersion <= 0 || !"READY".equals(status))
      throw new IllegalArgumentException("Unsupported backend endpoint");
    canonicalUuid(instanceId);
    canonicalUuid(storageId);
    URI uri = URI.create(baseUrl);
    if (!"http".equals(uri.getScheme())
        || !"127.0.0.1".equals(uri.getHost())
        || uri.getPort() < 1
        || uri.getPort() > 65535
        || uri.getRawUserInfo() != null
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null
        || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/")))
      throw new IllegalArgumentException("Backend discovery requires literal IPv4 loopback HTTP");
  }

  /** 起動環境の準備に関する正規一意な識別子を処理する。 */
  private static void canonicalUuid(String value) {
    if (value == null || !UUID.fromString(value).toString().equals(value))
      throw new IllegalArgumentException("Invalid backend identity");
  }

  /** 起動環境の準備で用いる参照先のURIを返す。 */
  public URI uri() {
    return URI.create(baseUrl);
  }

  /** 起動環境の準備において、同一起動単位の識別子に該当するか判定する。 */
  public boolean sameInstance(BackendEndpoint other) {
    return equals(other);
  }
}
