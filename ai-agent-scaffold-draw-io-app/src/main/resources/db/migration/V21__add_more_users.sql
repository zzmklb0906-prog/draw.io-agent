-- V21: 批量初始化系统研发、测试、架构师与演示用户账号
CREATE EXTENSION IF NOT EXISTS pgcrypto;

INSERT INTO app_user (username, password_hash, display_name, status, roles)
VALUES
    ('developer', crypt('dev123456', gen_salt('bf', 10)), '高级研发工程师', 'ACTIVE', '["DEVELOPER","USER"]'),
    ('tester', crypt('test123456', gen_salt('bf', 10)), '自动化测试员', 'ACTIVE', '["TESTER","USER"]'),
    ('architect', crypt('arch123456', gen_salt('bf', 10)), '系统架构师', 'ACTIVE', '["ARCHITECT","USER"]'),
    ('designer', crypt('draw123456', gen_salt('bf', 10)), 'UI/UX 绘图设计师', 'ACTIVE', '["DESIGNER","USER"]'),
    ('user1', crypt('user123456', gen_salt('bf', 10)), '普通用户 Alice', 'ACTIVE', '["USER"]'),
    ('user2', crypt('user123456', gen_salt('bf', 10)), '普通用户 Bob', 'ACTIVE', '["USER"]')
ON CONFLICT (username) DO NOTHING;
