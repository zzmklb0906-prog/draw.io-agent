import { createBrowserRouter, Navigate } from 'react-router-dom';
import { ProtectedRoute } from '../features/auth/components/ProtectedRoute';
import { LoginPage } from '../pages/LoginPage';
import { NotFoundPage } from '../pages/NotFoundPage';
import { RouteErrorPage } from '../pages/RouteErrorPage';
import { WorkspacePage } from '../pages/WorkspacePage';
import { MonitorPage } from '../pages/MonitorPage';
import { MemoryPage } from '../pages/MemoryPage';
import { EvalPage } from '../pages/EvalPage';

export const router = createBrowserRouter([
  { path: '/', element: <Navigate to="/workspace" replace />, errorElement: <RouteErrorPage /> },
  { path: '/login', element: <LoginPage />, errorElement: <RouteErrorPage /> },
  {
    element: <ProtectedRoute />,
    errorElement: <RouteErrorPage />,
    children: [{ path: '/workspace', element: <WorkspacePage /> }, { path: '/monitor', element: <MonitorPage /> }, { path: '/eval', element: <EvalPage /> }, { path: '/memories', element: <MemoryPage /> }],
  },
  { path: '*', element: <NotFoundPage /> },
]);
