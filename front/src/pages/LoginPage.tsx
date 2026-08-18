import { useState, type FormEvent } from 'react';
import { Navigate, useNavigate } from 'react-router-dom';
import { useAuthStore } from '../features/auth/auth.store';

export function LoginPage() {
  const user = useAuthStore((state) => state.user);
  const login = useAuthStore((state) => state.login);
  const navigate = useNavigate();
  const [username, setUsername] = useState('admin');
  const [password, setPassword] = useState('admin');
  const [error, setError] = useState('');

  if (user) return <Navigate to="/workspace" replace />;

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setError('');
    const res = await login(username.trim(), password);
    if (res.success) {
      navigate('/workspace', { replace: true });
    } else {
      setError(res.error || '演示账号或密码错误，请使用 admin / admin。');
    }
  };

  return (
    <main className="login-page">
      <section className="login-hero">
        <div className="brand-lockup"><span>AI</span><strong>Draw.io Agent</strong></div>
        <div>
          <p className="eyebrow">自然语言驱动的专业制图工作台</p>
          <h1>从想法到可编辑图表，保持思路流动。</h1>
          <p className="hero-copy">多智能体分析需求、规划节点并生成标准 Draw.io XML，结果可在编辑器中继续调整和导出。</p>
        </div>
        <div className="feature-strip"><span>需求分析</span><i>→</i><span>结构生成</span><i>→</i><span>可视化编辑</span></div>
      </section>
      <section className="login-panel">
        <form className="login-card" onSubmit={submit}>
          <p className="eyebrow">欢迎回来</p>
          <h2>登录工作台</h2>
          <p className="demo-note">由本地后端签发短期会话 Token；生产环境建议替换为 Spring Security/OIDC。</p>
          <label className="field"><span>用户名</span><input autoFocus autoComplete="username" value={username} onChange={(e) => setUsername(e.target.value)} /></label>
          <label className="field"><span>密码</span><input type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} /></label>
          {error && <p className="form-error" role="alert">{error}</p>}
          <button className="button primary wide" type="submit">进入工作台</button>
          <p className="login-hint">演示账号：admin / admin。密码不会被保存。</p>
        </form>
      </section>
    </main>
  );
}
