import React, { lazy, Suspense } from 'react'
import { HashRouter, Routes, Route, useLocation } from 'react-router-dom'
import { AnimatePresence } from 'framer-motion'
import { Spin } from 'antd'
import AppLayout from './components/AppLayout'
import HomeView from './views/HomeView'

const DetailView = lazy(() => import('./views/DetailView'))
const PlayerView = lazy(() => import('./views/PlayerView'))
const HistoryView = lazy(() => import('./views/HistoryView'))
const LiveView = lazy(() => import('./views/LiveView'))

function RouteFallback() {
  return (
    <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '60vh' }}>
      <Spin />
    </div>
  )
}

function AnimatedRoutes() {
  const location = useLocation()
  return (
    <Suspense fallback={<RouteFallback />}>
      <AnimatePresence mode="wait">
        <Routes location={location} key={location.pathname}>
          <Route path="/" element={<HomeView />} />
          <Route path="/detail" element={<DetailView />} />
          <Route path="/player" element={<PlayerView />} />
          <Route path="/history" element={<HistoryView />} />
          <Route path="/live" element={<LiveView />} />
        </Routes>
      </AnimatePresence>
    </Suspense>
  )
}

export default function App() {
  return (
    <HashRouter>
      <AppLayout>
        <AnimatedRoutes />
      </AppLayout>
    </HashRouter>
  )
}
