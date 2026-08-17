import { Link } from 'react-router-dom';

export function NotFoundPage() {
  return <main className="not-found"><p className="eyebrow">404</p><h1>页面不存在</h1><p>这个地址没有对应的工作区。</p><Link className="button primary" to="/workspace">返回工作台</Link></main>;
}
