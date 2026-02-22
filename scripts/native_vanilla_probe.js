#!/usr/bin/env node
// Vanilla-like protocol probe using minecraft-protocol client.
// Usage: node scripts/native_vanilla_probe.js <host> <port> [version]
const mc = require('minecraft-protocol')

const host = process.argv[2] || '127.0.0.1'
const port = Number(process.argv[3] || 25570)
const version = process.argv[4] || '1.21.11'

let sawPlayLogin = false
let sawPlayPosition = false

const client = mc.createClient({
  host,
  port,
  username: 'ClockworkProbe',
  auth: 'offline',
  version
})

const timeout = setTimeout(() => {
  console.error('probe timeout before play bootstrap')
  process.exit(3)
}, 12000)

client.on('packet', (data, meta, state) => {
  if (state !== 'play') return
  if (meta?.name === 'login') sawPlayLogin = true
  if (meta?.name === 'position') sawPlayPosition = true
  if (sawPlayLogin && sawPlayPosition) {
    clearTimeout(timeout)
    console.log('probe ok: reached play login + position')
    client.end('probe complete')
    process.exit(0)
  }
})

client.on('end', () => {
  if (sawPlayLogin && sawPlayPosition) return
  clearTimeout(timeout)
  console.error('probe ended before expected play packets')
  process.exit(4)
})

client.on('error', (error) => {
  clearTimeout(timeout)
  console.error(`probe error: ${error?.message || error}`)
  process.exit(2)
})
