UPDATE system_user
SET username='彭于晏',
    display_name='彭于晏',
    password_hash='$2a$12$3iaHG5ZO/pzjUGM9xE5ae.ymsvsPATUoAPZi7qCUXiv9Sly7Lwp6u',
    update_time=NOW()
WHERE user_id=1;
SELECT user_id, username, display_name, role, status, password_hash FROM system_user WHERE user_id=1;