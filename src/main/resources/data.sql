-- 外部キー制約を一時無効化
SET REFERENTIAL_INTEGRITY FALSE;

-- 全テーブルのデータを削除（AUTO_INCREMENTもリセット）
TRUNCATE TABLE annotation_attributes;
TRUNCATE TABLE annotations;
TRUNCATE TABLE endpoints;
TRUNCATE TABLE members;
TRUNCATE TABLE class_dependencies;
TRUNCATE TABLE classes;
TRUNCATE TABLE packages;
TRUNCATE TABLE projects;
TRUNCATE TABLE dependency_kinds;
TRUNCATE TABLE http_methods;
TRUNCATE TABLE class_types;
TRUNCATE TABLE member_types;

-- 外部キー制約を再有効化
SET REFERENTIAL_INTEGRITY TRUE;

-- 依存タイプの初期データ投入
INSERT INTO dependency_kinds (code, description) VALUES ('001_001', '継承（extends）');
INSERT INTO dependency_kinds (code, description) VALUES ('001_002', '実装（implements）');
INSERT INTO dependency_kinds (code, description) VALUES ('001_003', 'ジェネリクス型参照');
INSERT INTO dependency_kinds (code, description) VALUES ('001_004', '例外型依存');
INSERT INTO dependency_kinds (code, description) VALUES ('001_005', 'メソッド呼び出し');
INSERT INTO dependency_kinds (code, description) VALUES ('001_006', '戻り値型依存');
INSERT INTO dependency_kinds (code, description) VALUES ('001_007', '引数型依存');
INSERT INTO dependency_kinds (code, description) VALUES ('001_008', '静的メソッド依存');
INSERT INTO dependency_kinds (code, description) VALUES ('001_009', 'コンポジション（保持）');
INSERT INTO dependency_kinds (code, description) VALUES ('001_010', '集合保持');
INSERT INTO dependency_kinds (code, description) VALUES ('001_011', '定数参照');
INSERT INTO dependency_kinds (code, description) VALUES ('002_001', 'SetterDI');
INSERT INTO dependency_kinds (code, description) VALUES ('002_002', '@Bean提供');
INSERT INTO dependency_kinds (code, description) VALUES ('002_003', 'コンストラクタDI');
INSERT INTO dependency_kinds (code, description) VALUES ('002_004', 'フィールドDI');
INSERT INTO dependency_kinds (code, description) VALUES ('002_005', 'コントローラ定義');
INSERT INTO dependency_kinds (code, description) VALUES ('002_006', 'サービス層定義');
INSERT INTO dependency_kinds (code, description) VALUES ('002_007', 'リポジトリ層定義');
INSERT INTO dependency_kinds (code, description) VALUES ('003_001', 'JPAリポジトリ');
INSERT INTO dependency_kinds (code, description) VALUES ('003_002', 'JPAエンティティ');
INSERT INTO dependency_kinds (code, description) VALUES ('003_003', 'クエリメソッド');
INSERT INTO dependency_kinds (code, description) VALUES ('003_004', 'DTO');
INSERT INTO dependency_kinds (code, description) VALUES ('003_005', 'マッパー');
INSERT INTO dependency_kinds (code, description) VALUES ('004_001', '@Value注入');
INSERT INTO dependency_kinds (code, description) VALUES ('004_002', '構成プロパティ');
INSERT INTO dependency_kinds (code, description) VALUES ('004_003', 'プロファイル条件');
INSERT INTO dependency_kinds (code, description) VALUES ('004_004', 'オートコンフィグ');
INSERT INTO dependency_kinds (code, description) VALUES ('004_005', 'ビルド依存');
INSERT INTO dependency_kinds (code, description) VALUES ('005_001', 'アプリイベント購読');
INSERT INTO dependency_kinds (code, description) VALUES ('005_002', 'HTTPクライアント');
INSERT INTO dependency_kinds (code, description) VALUES ('005_003', 'メッセージング');
INSERT INTO dependency_kinds (code, description) VALUES ('006_001', 'トランザクション');
INSERT INTO dependency_kinds (code, description) VALUES ('006_002', '横断的関心事');
INSERT INTO dependency_kinds (code, description) VALUES ('006_003', 'ログ/メトリクス');
INSERT INTO dependency_kinds (code, description) VALUES ('006_004', 'Bean Validation');
INSERT INTO dependency_kinds (code, description) VALUES ('007_001', 'SecurityFilterChain構成');
INSERT INTO dependency_kinds (code, description) VALUES ('007_002', 'HttpSecurityルール');
INSERT INTO dependency_kinds (code, description) VALUES ('007_003', 'UserDetails');
INSERT INTO dependency_kinds (code, description) VALUES ('007_004', 'UserDetailsService');
INSERT INTO dependency_kinds (code, description) VALUES ('007_005', 'PasswordEncoder');
INSERT INTO dependency_kinds (code, description) VALUES ('007_006', 'AuthenticationManager');
INSERT INTO dependency_kinds (code, description) VALUES ('007_007', 'AuthenticationProvider');
INSERT INTO dependency_kinds (code, description) VALUES ('007_008', 'OncePerRequestFilter');
INSERT INTO dependency_kinds (code, description) VALUES ('007_009', 'メソッドセキュリティ');
INSERT INTO dependency_kinds (code, description) VALUES ('007_010', 'ロール/権限');
INSERT INTO dependency_kinds (code, description) VALUES ('007_011', 'SecurityContext');
INSERT INTO dependency_kinds (code, description) VALUES ('007_012', 'Session管理');
INSERT INTO dependency_kinds (code, description) VALUES ('007_013', 'トークン抽出');
INSERT INTO dependency_kinds (code, description) VALUES ('007_014', '署名/検証');
INSERT INTO dependency_kinds (code, description) VALUES ('007_015', 'クレーム→権限');
INSERT INTO dependency_kinds (code, description) VALUES ('007_016', 'ログイン/ログアウト');
INSERT INTO dependency_kinds (code, description) VALUES ('007_017', 'CORS/CSRF');
INSERT INTO dependency_kinds (code, description) VALUES ('008_001', 'Lombok');
INSERT INTO dependency_kinds (code, description) VALUES ('008_002', 'Jackson');
INSERT INTO dependency_kinds (code, description) VALUES ('009_001', 'Controller→Service');
INSERT INTO dependency_kinds (code, description) VALUES ('009_002', 'Service→Repository');
INSERT INTO dependency_kinds (code, description) VALUES ('009_003', 'Repository→Entity');
INSERT INTO dependency_kinds (code, description) VALUES ('009_004', 'パス/パラメータ依存');
INSERT INTO dependency_kinds (code, description) VALUES ('009_005', 'テストスライス');
INSERT INTO dependency_kinds (code, description) VALUES ('009_006', 'Model→Controller');
INSERT INTO dependency_kinds (code, description) VALUES ('011_001', 'Controller→Form');
INSERT INTO dependency_kinds (code, description) VALUES ('011_002', 'Controller→ViewModel');

-- HTTPメソッドの初期データ投入
INSERT INTO http_methods (method_name) VALUES ('GET');
INSERT INTO http_methods (method_name) VALUES ('POST');
INSERT INTO http_methods (method_name) VALUES ('PUT');
INSERT INTO http_methods (method_name) VALUES ('DELETE');
INSERT INTO http_methods (method_name) VALUES ('PATCH');

-- クラスタイプの初期データ投入
INSERT INTO class_types (code, description) VALUES ('CLASS', 'クラス');
INSERT INTO class_types (code, description) VALUES ('INTERFACE', 'インターフェース');
INSERT INTO class_types (code, description) VALUES ('ENUM', '列挙型');
INSERT INTO class_types (code, description) VALUES ('ANNOTATION', 'アノテーション');
INSERT INTO class_types (code, description) VALUES ('RECORD', 'レコード');

-- メンバータイプの初期データ投入
INSERT INTO member_types (code, description) VALUES ('FIELD', 'フィールド');
INSERT INTO member_types (code, description) VALUES ('METHOD', 'メソッド');
INSERT INTO member_types (code, description) VALUES ('CONSTRUCTOR', 'コンストラクタ');

