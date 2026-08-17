import { isRouteErrorResponse, Link, useRouteError } from 'react-router-dom';

export function RouteErrorPage() {
  const error = useRouteError();
  const message = isRouteErrorResponse(error)
    ? `${error.status} ${error.statusText}`
    : error instanceof Error
      ? error.message
      : '页面发生未知错误';

  return (
    <main className="not-found" role="alert">
      <p className="eyebrow">APPLICATION ERROR</p>
      <h1>工作台暂时无法继续</h1>
      <p>{message}</p>
      <p className="demo-note">当前请求已断开。刷新页面后可以新建会话重新尝试。</p>
      <div className="error-actions">
        <button className="button" onClick={() => window.location.reload()}>刷新页面</button>
        <Link className="button primary" to="/workspace">返回工作台</Link>
      </div>
    </main>
  );
}
