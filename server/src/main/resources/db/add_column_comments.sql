-- ============================================================
-- eye_monitor 数据表字段注释（备注）补全
-- 运行方式: mysql -ueye -p'...' eye_monitor < add_column_comments.sql
-- 幂等：重复执行无副作用（COMMENT 会被覆盖为相同值）。
-- 新表 eye_app_names 由 JPA 实体创建时自带字段注释，无需在此处理。
-- ============================================================

-- eye_users 用户表
ALTER TABLE eye_users
  MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN username VARCHAR(64) NOT NULL COMMENT '用户名（唯一，登录账号）',
  MODIFY COLUMN password_hash VARCHAR(100) NOT NULL COMMENT '密码哈希（bcrypt）',
  MODIFY COLUMN nickname VARCHAR(64) NOT NULL COMMENT '昵称',
  MODIFY COLUMN avatar VARCHAR(255) COMMENT '头像路径（./data/avatar/{userId}.jpg）',
  MODIFY COLUMN gender VARCHAR(16) COMMENT '性别 female|male|null',
  MODIFY COLUMN birthday DATE COMMENT '生日 yyyy-MM-dd',
  MODIFY COLUMN bio VARCHAR(255) COMMENT '个性签名',
  MODIFY COLUMN device_id VARCHAR(64) COMMENT '当前登录设备ID（单设备登录）',
  MODIFY COLUMN ver INT NOT NULL COMMENT 'JWT token 族版本号（登录+1，旧token失效）',
  MODIFY COLUMN created_at BIGINT NOT NULL COMMENT '注册时间（epoch ms）';

-- eye_pairs 配对表
ALTER TABLE eye_pairs
  MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN pair_code VARCHAR(16) NOT NULL COMMENT '6位配对码（唯一）',
  MODIFY COLUMN user_a BIGINT COMMENT '设备A用户ID',
  MODIFY COLUMN user_b BIGINT COMMENT '设备B用户ID',
  MODIFY COLUMN status VARCHAR(16) NOT NULL COMMENT '配对状态 PENDING|COMPLETE',
  MODIFY COLUMN created_at BIGINT NOT NULL COMMENT '创建时间（epoch ms）';

-- eye_chat_messages 聊天记录表
ALTER TABLE eye_chat_messages
  MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN pair_code VARCHAR(16) NOT NULL COMMENT '配对码',
  MODIFY COLUMN from_user BIGINT COMMENT '发送方用户ID',
  MODIFY COLUMN text VARCHAR(2000) NOT NULL COMMENT '消息内容',
  MODIFY COLUMN is_system TINYINT(1) NOT NULL COMMENT '是否系统提示（1=系统/居中提示）',
  MODIFY COLUMN ts BIGINT NOT NULL COMMENT '发送时间（epoch ms）';

-- eye_location_points 位置轨迹点表
ALTER TABLE eye_location_points
  MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN pair_code VARCHAR(16) NOT NULL COMMENT '配对码',
  MODIFY COLUMN user_id BIGINT COMMENT '所属用户ID',
  MODIFY COLUMN device_id VARCHAR(64) COMMENT '来源设备ID（轨迹回放 device 参数）',
  MODIFY COLUMN lat DOUBLE NOT NULL COMMENT '纬度',
  MODIFY COLUMN lng DOUBLE NOT NULL COMMENT '经度',
  MODIFY COLUMN accuracy FLOAT NOT NULL COMMENT '定位精度（米）',
  MODIFY COLUMN ts BIGINT NOT NULL COMMENT '定位时间（epoch ms）';

-- eye_anniversaries 纪念日表
ALTER TABLE eye_anniversaries
  MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN pair_code VARCHAR(16) NOT NULL COMMENT '配对码',
  MODIFY COLUMN name VARCHAR(64) NOT NULL COMMENT '纪念日名称',
  MODIFY COLUMN date DATE NOT NULL COMMENT '纪念日期 yyyy-MM-dd',
  MODIFY COLUMN is_repeat TINYINT(1) NOT NULL COMMENT '是否每年重复（1=每年重复）',
  MODIFY COLUMN updated_at BIGINT NOT NULL COMMENT '最后修改时间（epoch ms）';

-- eye_media_files 媒体文件元数据表
ALTER TABLE eye_media_files
  MODIFY COLUMN file_id VARCHAR(64) NOT NULL COMMENT '文件ID（磁盘文件名）',
  MODIFY COLUMN pair_code VARCHAR(16) NOT NULL COMMENT '配对码',
  MODIFY COLUMN uploader BIGINT COMMENT '上传者用户ID',
  MODIFY COLUMN file_name VARCHAR(255) COMMENT '原始文件名',
  MODIFY COLUMN mime VARCHAR(64) COMMENT 'MIME 类型',
  MODIFY COLUMN size BIGINT NOT NULL COMMENT '文件大小（字节）',
  MODIFY COLUMN duration BIGINT COMMENT '视频时长（毫秒，非视频为NULL）',
  MODIFY COLUMN path VARCHAR(255) COMMENT '磁盘相对路径（./data/media/yyyy/MM/dd/...）',
  MODIFY COLUMN created_at BIGINT NOT NULL COMMENT '上传时间（epoch ms）',
  MODIFY COLUMN deleted TINYINT(1) NOT NULL COMMENT '是否已删除（逻辑删除标记）';

-- eye_fences 电子围栏表
ALTER TABLE eye_fences
  MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN pair_code VARCHAR(16) NOT NULL COMMENT '配对码',
  MODIFY COLUMN owner_user BIGINT COMMENT '围栏创建者用户ID',
  MODIFY COLUMN name VARCHAR(64) NOT NULL COMMENT '围栏名称（家/公司等）',
  MODIFY COLUMN center_lat DOUBLE NOT NULL COMMENT '围栏中心纬度',
  MODIFY COLUMN center_lng DOUBLE NOT NULL COMMENT '围栏中心经度',
  MODIFY COLUMN radius DOUBLE NOT NULL COMMENT '围栏半径（米，100m~50km）',
  MODIFY COLUMN enabled TINYINT(1) NOT NULL COMMENT '是否启用（1=启用）',
  MODIFY COLUMN created_at BIGINT NOT NULL COMMENT '创建时间（epoch ms）';

-- eye_sos_logs SOS 求助留痕表（可选表，存在则注释）
ALTER TABLE eye_sos_logs
  MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  MODIFY COLUMN pair_code VARCHAR(16) NOT NULL COMMENT '配对码',
  MODIFY COLUMN from_user BIGINT COMMENT '发起方用户ID',
  MODIFY COLUMN text VARCHAR(500) COMMENT '求救语',
  MODIFY COLUMN lat DOUBLE COMMENT '附带纬度（无定位为NULL）',
  MODIFY COLUMN lng DOUBLE COMMENT '附带经度（无定位为NULL）',
  MODIFY COLUMN ts BIGINT NOT NULL COMMENT '触发时间（epoch ms）';
