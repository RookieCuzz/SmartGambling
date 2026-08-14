'use strict'

const fs = require('node:fs')
const path = require('node:path')
const readline = require('node:readline')
const { spawn, spawnSync } = require('node:child_process')
const { DatabaseSync } = require('node:sqlite')
const mineflayer = require('mineflayer')
const { Vec3 } = require('vec3')

const ROOT = __dirname
const SERVER_DIR = path.resolve(process.env.SMARTGAMBLING_SERVER_DIR || path.join(ROOT, 'server'))
const MC_VERSION = process.env.SMARTGAMBLING_MC_VERSION || '1.21.4'
const PAPER_JAR = process.env.SMARTGAMBLING_PAPER_JAR || 'paper-1.21.4.jar'
const HOST = '127.0.0.1'
const PORT = 25579
const timestamp = new Date().toISOString().replaceAll(':', '-').replaceAll('.', '-')
const ARTIFACT_DIR = path.join(ROOT, 'artifacts', timestamp)
fs.mkdirSync(ARTIFACT_DIR, { recursive: true })

const stripAnsi = (value) => String(value).replace(/\x1B(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~])/g, '')
const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

function assert(condition, message) {
  if (!condition) throw new Error(`Assertion failed: ${message}`)
}

function readJson(file) {
  return JSON.parse(fs.readFileSync(file, 'utf8'))
}

function forcedSlotConfigText(source, enabled) {
  const hadBom = source.startsWith('\uFEFF')
  const body = hadBom ? source.slice(1) : source
  const eol = body.includes('\r\n') ? '\r\n' : '\n'
  const lines = body.split(/\r?\n/)
  const scalar = (name, value) => `    ${name}: ${value}`
  const contentLine = (line) => line.trim() !== '' && !line.trimStart().startsWith('#')
  const indentation = (line) => line.match(/^\s*/)[0].length

  const testingIndex = lines.findIndex((line) => /^Testing:\s*(?:#.*)?$/.test(line))
  if (testingIndex === -1) {
    const block = [
      'Testing:',
      '  forcedSlotResults:',
      scalar('enabled', enabled ? 'true' : 'false'),
      scalar('expiresSeconds', '120'),
      ''
    ].join(eol)
    return `${hadBom ? '\uFEFF' : ''}${block}${body}`
  }

  let testingEnd = lines.length
  for (let index = testingIndex + 1; index < lines.length; index++) {
    if (contentLine(lines[index]) && indentation(lines[index]) === 0) {
      testingEnd = index
      break
    }
  }
  let forcedIndex = -1
  for (let index = testingIndex + 1; index < testingEnd; index++) {
    if (/^ {2}forcedSlotResults:\s*(?:#.*)?$/.test(lines[index])) {
      forcedIndex = index
      break
    }
  }
  if (forcedIndex === -1) {
    lines.splice(testingEnd, 0,
      '  forcedSlotResults:',
      scalar('enabled', enabled ? 'true' : 'false'),
      scalar('expiresSeconds', '120'))
    return `${hadBom ? '\uFEFF' : ''}${lines.join(eol)}`
  }

  let forcedEnd = testingEnd
  for (let index = forcedIndex + 1; index < testingEnd; index++) {
    if (contentLine(lines[index]) && indentation(lines[index]) <= 2) {
      forcedEnd = index
      break
    }
  }
  const setScalar = (name, value) => {
    const pattern = new RegExp(`^ {4}${name}:`)
    for (let index = forcedIndex + 1; index < forcedEnd; index++) {
      if (pattern.test(lines[index])) {
        lines[index] = scalar(name, value)
        return
      }
    }
    lines.splice(forcedEnd, 0, scalar(name, value))
    forcedEnd++
  }
  setScalar('enabled', enabled ? 'true' : 'false')
  setScalar('expiresSeconds', '120')
  return `${hadBom ? '\uFEFF' : ''}${lines.join(eol)}`
}

function isolateForcedSlotConfig(enabled) {
  const file = path.join(SERVER_DIR, 'plugins', 'SmartGambling', 'config.yml')
  if (!fs.existsSync(file)) return null
  const original = fs.readFileSync(file)
  const updated = forcedSlotConfigText(original.toString('utf8'), enabled)
  fs.writeFileSync(file, updated, 'utf8')
  process.stdout.write(`[config] forced slot test mode=${enabled}; original config will be restored after shutdown\n`)
  let restored = false
  return {
    restore() {
      if (restored) return
      fs.writeFileSync(file, original)
      restored = true
      process.stdout.write('[config] restored original SmartGambling config bytes\n')
    }
  }
}

function ledgerSnapshot() {
  const file = path.join(SERVER_DIR, 'plugins', 'SmartGambling', 'economy-ledger.db')
  if (!fs.existsSync(file)) return { wagers: [], transactions: [] }
  const database = new DatabaseSync(file, { readOnly: true })
  try {
    database.exec('PRAGMA busy_timeout=5000; PRAGMA query_only=ON')
    return {
      wagers: database.prepare('SELECT * FROM wagers ORDER BY created_at, id').all(),
      transactions: database.prepare('SELECT * FROM transactions ORDER BY created_at, id').all()
    }
  } finally {
    database.close()
  }
}

function machinesFromData() {
  const file = path.join(SERVER_DIR, 'plugins', 'SmartGambling', 'data.json')
  if (!fs.existsSync(file)) return []
  const root = readJson(file)
  const machines = []
  for (const [world, chunks] of Object.entries(root.worlds || {})) {
    for (const chunk of chunks || []) {
      for (const machine of chunk.machines || []) {
        machines.push({ world, chunkX: chunk.chunkX, chunkZ: chunk.chunkZ, ...machine })
      }
    }
  }
  return machines
}

function machineIdentity(machines) {
  return machines.map((machine) => ({
    id: machine.id,
    type: machine.type,
    blocks: machine.blocks,
    entities: (machine.entities || []).map((entity) => ({
      role: entity.role,
      uuid: entity.uuid
    })).sort((left, right) => left.role.localeCompare(right.role))
  })).sort((left, right) => left.id.localeCompare(right.id))
}

async function waitUntil(predicate, timeoutMs, description, intervalMs = 100) {
  const deadline = Date.now() + timeoutMs
  let lastError
  while (Date.now() < deadline) {
    try {
      const value = await predicate()
      if (value) return value
    } catch (error) {
      lastError = error
    }
    await delay(intervalMs)
  }
  const suffix = lastError ? `; last error: ${lastError.message}` : ''
  throw new Error(`Timed out waiting for ${description}${suffix}`)
}

class PaperServer {
  constructor() {
    this.child = null
    this.lines = []
    this.waiters = new Set()
    this.logStream = fs.createWriteStream(path.join(ARTIFACT_DIR, 'server.log'), { flags: 'a' })
  }

  async start() {
    if (this.child && this.child.exitCode === null) throw new Error('Paper is already running')
    const paperPath = path.join(SERVER_DIR, PAPER_JAR)
    if (!fs.existsSync(paperPath)) {
      throw new Error(`Paper ${MC_VERSION} JAR not found: ${paperPath}`)
    }
    this.child = null
    this.lines = []
    const args = [
      '-Xms512M',
      '-Xmx1536M',
      '-Dfile.encoding=UTF-8',
      '-Dcom.mojang.eula.agree=true',
      '-jar',
      PAPER_JAR,
      '--nogui'
    ]
    this.child = spawn('java', args, {
      cwd: SERVER_DIR,
      windowsHide: true,
      stdio: ['pipe', 'pipe', 'pipe']
    })
    this.child.on('error', (error) => this.record(`[process error] ${error.stack || error}`))
    this.child.on('exit', (code, signal) => this.record(`[process exit] code=${code} signal=${signal}`))
    this.consume(this.child.stdout, 'stdout')
    this.consume(this.child.stderr, 'stderr')
    await this.waitFor(/Done \([^)]+\)! For help, type "help"/, 240_000, 'Paper startup')
    await this.waitFor(/SmartGambling.*(?:initialized|enabled|CraftEngine)/i, 60_000, 'SmartGambling readiness')
  }

  consume(stream, source) {
    const reader = readline.createInterface({ input: stream })
    reader.on('line', (line) => this.record(`[${source}] ${stripAnsi(line)}`))
  }

  record(line) {
    const entry = `${new Date().toISOString()} ${line}`
    this.lines.push(entry)
    this.logStream.write(`${entry}\n`)
    process.stdout.write(`${entry}\n`)
    for (const waiter of [...this.waiters]) {
      if (waiter.pattern.test(entry)) {
        this.waiters.delete(waiter)
        clearTimeout(waiter.timer)
        waiter.resolve(entry)
      }
    }
  }

  waitFor(pattern, timeoutMs, description) {
    for (const line of this.lines) {
      pattern.lastIndex = 0
      if (pattern.test(line)) return Promise.resolve(line)
    }
    return new Promise((resolve, reject) => {
      const waiter = { pattern, resolve, reject, timer: null }
      waiter.timer = setTimeout(() => {
        this.waiters.delete(waiter)
        reject(new Error(`Timed out waiting for ${description}: ${pattern}`))
      }, timeoutMs)
      this.waiters.add(waiter)
    })
  }

  command(command) {
    if (!this.child || this.child.exitCode !== null) throw new Error(`Cannot send command to stopped server: ${command}`)
    this.record(`[stdin] ${command}`)
    this.child.stdin.write(`${command}\n`)
  }

  async stop(closeLog = true) {
    if (!this.child || this.child.exitCode !== null) {
      if (closeLog) this.logStream.end()
      return
    }
    this.command('stop')
    await Promise.race([
      new Promise((resolve) => this.child.once('exit', resolve)),
      delay(60_000).then(() => {
        if (this.child.exitCode === null) this.child.kill('SIGKILL')
      })
    ])
    if (closeLog) this.logStream.end()
  }

  async hardKill() {
    if (!this.child || this.child.exitCode !== null) return
    const child = this.child
    const findListenerPid = () => {
      const result = spawnSync('netstat', ['-ano', '-p', 'tcp'], {
        encoding: 'utf8', windowsHide: true
      })
      if (result.status !== 0) throw new Error(`netstat failed: ${result.stderr}`)
      for (const line of result.stdout.split(/\r?\n/)) {
        const fields = line.trim().split(/\s+/)
        if (fields.length >= 5 && fields[0].toUpperCase() === 'TCP'
            && fields[1].endsWith(`:${PORT}`)
            && fields.at(-2).toUpperCase() === 'LISTENING') {
          const pid = Number(fields.at(-1))
          if (Number.isInteger(pid) && pid > 0) return pid
        }
      }
      return null
    }
    const listenerPid = findListenerPid()
    if (!listenerPid) throw new Error(`Could not identify the Paper listener PID on ${HOST}:${PORT}`)
    this.record(`[hard-kill] terminating Paper listener pid=${listenerPid} (launcher=${child.pid}) without plugin disable/save`)
    const serverExit = new Promise((resolve) => child.once('exit', resolve))
    // On Windows the java launcher can own a descendant JVM.  Killing only
    // the launcher leaves Paper alive with the world lock, so terminate the
    // exact process tree rooted at the PID this harness created.
    const killer = spawn('taskkill', ['/PID', String(listenerPid), '/T', '/F'], {
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe']
    })
    this.consume(killer.stdout, 'taskkill')
    this.consume(killer.stderr, 'taskkill-error')
    const killCode = await new Promise((resolve) => killer.once('exit', resolve))
    if (killCode !== 0) throw new Error(`taskkill failed for Paper listener process tree ${listenerPid}: ${killCode}`)
    if (child.exitCode === null && listenerPid !== child.pid) {
      const launcherKiller = spawn('taskkill', ['/PID', String(child.pid), '/T', '/F'], {
        windowsHide: true, stdio: ['ignore', 'pipe', 'pipe']
      })
      this.consume(launcherKiller.stdout, 'taskkill-launcher')
      this.consume(launcherKiller.stderr, 'taskkill-launcher-error')
      await new Promise((resolve) => launcherKiller.once('exit', resolve))
    }
    await Promise.race([
      serverExit,
      delay(15_000).then(() => { throw new Error(`Paper process tree ${child.pid} did not exit`) })
    ])
    await waitUntil(() => findListenerPid() === null, 15_000,
      `Paper listener ${HOST}:${PORT} to disappear`, 250)
    this.child = null
    await delay(5_000)
  }
}

class TestBot {
  constructor(name) {
    this.name = name
    this.bot = null
    this.messages = []
    this.windows = []
    this.bossBars = []
    this.logStream = fs.createWriteStream(path.join(ARTIFACT_DIR, `${name}.log`), { flags: 'a' })
  }

  record(kind, value) {
    const line = `${new Date().toISOString()} [${kind}] ${stripAnsi(value)}`
    this.logStream.write(`${line}\n`)
    process.stdout.write(`[${this.name}] ${line}\n`)
  }

  async connect() {
    this.bot = mineflayer.createBot({
      host: HOST,
      port: PORT,
      username: this.name,
      auth: 'offline',
      version: MC_VERSION,
      checkTimeoutInterval: 30_000
    })
    this.bot.on('resourcePack', () => {
      this.record('resourcePack', 'accepted protocol request (not visually rendered by Mineflayer)')
      this.bot.acceptResourcePack()
    })
    this.bot.on('messagestr', (message, position) => {
      const entry = { at: Date.now(), message: stripAnsi(message), position }
      this.messages.push(entry)
      this.record(`message:${position}`, entry.message)
    })
    this.bot.on('actionBar', (message) => this.record('actionBar', message.toString()))
    this.bot.on('bossBarCreated', (bossBar) => {
      const title = stripAnsi(bossBar.title)
      this.bossBars.push({ at: Date.now(), title })
      this.record('bossBarCreated', title)
    })
    this.bot.on('bossBarUpdated', (bossBar) => {
      const title = stripAnsi(bossBar.title)
      this.bossBars.push({ at: Date.now(), title })
      this.record('bossBarUpdated', title)
    })
    this.bot.on('windowOpen', (window) => {
      this.windows.push(window)
      this.record('windowOpen', this.describeWindow(window))
      window.on('updateSlot', (slot, oldItem, newItem) => {
        this.record('updateSlot', `${slot}: ${this.describeItem(oldItem)} -> ${this.describeItem(newItem)}`)
      })
    })
    this.bot.on('windowClose', (window) => this.record('windowClose', this.describeWindow(window)))
    this.bot.on('kicked', (reason) => this.record('kicked', JSON.stringify(reason)))
    this.bot.on('error', (error) => this.record('error', error.stack || error.message))
    this.bot.on('end', (reason) => this.record('end', reason))
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`${this.name} spawn timed out`)), 30_000)
      this.bot.once('spawn', () => {
        clearTimeout(timer)
        this.record('spawn', this.bot.entity.position.toString())
        resolve()
      })
      this.bot.once('error', (error) => {
        clearTimeout(timer)
        reject(error)
      })
    })
  }

  describeItem(item) {
    if (!item) return 'empty'
    const custom = item.customName ? ` name=${item.customName}` : ''
    return `${item.name}x${item.count}${custom}`
  }

  describeWindow(window) {
    if (!window) return 'none'
    const occupied = []
    for (let slot = 0; slot < window.inventoryStart; slot++) {
      const item = window.slots[slot]
      if (item) occupied.push(`${slot}=${this.describeItem(item)}`)
    }
    return `id=${window.id} type=${window.type} title=${window.title} top=${window.inventoryStart} [${occupied.join(', ')}]`
  }

  chat(message) {
    this.record('chat>', message)
    this.bot.chat(message)
  }

  async waitMessage(pattern, timeoutMs = 10_000, after = 0) {
    return waitUntil(() => {
      for (let index = after; index < this.messages.length; index++) {
        pattern.lastIndex = 0
        if (pattern.test(this.messages[index].message)) return this.messages[index]
      }
      return null
    }, timeoutMs, `${this.name} message ${pattern}`)
  }

  async waitWindow(topSize, timeoutMs = 10_000, previous = null) {
    return waitUntil(() => {
      const window = this.bot.currentWindow
      return window && window !== previous && window.inventoryStart === topSize ? window : null
    }, timeoutMs, `${this.name} ${topSize}-slot window`)
  }

  async click(slot) {
    const window = this.bot.currentWindow
    if (!window) throw new Error(`${this.name} cannot click slot ${slot}: no window is open`)
    this.record('click', `${slot} in ${this.describeWindow(window)}`)
    await this.bot.simpleClick.leftMouse(slot)
  }

  async closeWindow() {
    const window = this.bot.currentWindow
    if (!window) return
    this.record('closeWindow>', this.describeWindow(window))
    this.bot.closeWindow(window)
    await waitUntil(() => !this.bot.currentWindow || this.bot.currentWindow !== window,
      5_000, `${this.name} close window`).catch(() => {})
  }

  async teleport(server, position) {
    server.command(`minecraft:tp ${this.name} ${position.x + 0.5} ${position.y + 1} ${position.z + 0.5}`)
    await waitUntil(() => this.bot.entity.position.distanceTo(position.offset(0.5, 1, 0.5)) < 2,
      10_000, `${this.name} teleport to ${position}`)
  }

  async activateBlock(position) {
    const block = await waitUntil(() => this.bot.blockAt(position), 10_000, `${this.name} block at ${position}`)
    this.record('activateBlock', `${block.name} ${position}`)
    await this.bot.activateBlock(block)
  }

  async disconnect() {
    if (this.bot && this.bot.player) this.bot.quit('integration test complete')
    await delay(100)
    this.logStream.end()
  }
}

async function bootstrap(server) {
  server.command('gamerule doDaylightCycle false')
  server.command('gamerule doWeatherCycle false')
  server.command('time set day')
  server.command('difficulty peaceful')
  server.command('minecraft:setworldspawn 0 80 0')
  server.command('minecraft:fill -12 79 -12 28 79 28 minecraft:stone')
  await delay(1_000)
}

async function prepareArena(server, bots) {
  server.command('minecraft:forceload add -2 -2 2 2')
  await delay(1_000)
  server.command('minecraft:fill -12 79 -12 28 79 28 minecraft:stone')
  server.command('minecraft:fill -12 80 -12 28 84 28 minecraft:air')
  await delay(1_000)
  for (let index = 0; index < bots.length; index++) {
    const bot = bots[index]
    server.command(`minecraft:tp ${bot.name} ${index * 2 + 0.5} 80 ${-5.5}`)
  }
  await delay(2_000)
}

async function createMachine(server, admin, type, position) {
  server.command(`minecraft:setblock ${position.x} ${position.y} ${position.z} minecraft:gold_block`)
  server.command(`minecraft:tp ${admin.name} ${position.x + 0.5} ${position.y + 1} ${position.z - 2.5}`)
  await delay(500)
  const start = admin.messages.length
  const bossBarStart = admin.bossBars.length
  admin.chat(`/sg add ${type}`)
  await admin.waitMessage(/创建向导已启动/i, 10_000, start)
  await waitUntil(() => admin.bossBars.slice(bossBarStart)
    .find((entry) => /机器创建向导|左键设置机器原点/.test(entry.title)),
  10_000, `${type} Chinese creation BossBar`)
  const block = await waitUntil(() => {
    const candidate = admin.bot.blockAt(position)
    return candidate && candidate.name === 'gold_block' ? candidate : null
  }, 10_000, `${type} selection block`)
  const selected = admin.waitMessage(/设为机器原点/i, 10_000, start)
  admin.record('selectOrigin', `${block.name} ${position}`)
  // The creation wand uses left click for origin. The public dig path emits
  // that interaction; the plugin cancels the actual block break.
  try {
    await Promise.race([
      admin.bot.dig(block, true, 'raycast').catch((error) => admin.record('dig', `被向导取消（符合预期）: ${error.message}`)),
      delay(2_000)
    ])
  } catch (error) {
    admin.record('dig', `已忽略选点中断: ${error.message}`)
  }
  await selected
  const rotateStart = admin.messages.length
  admin.chat('/sg rotate right')
  await admin.waitMessage(/预览朝向已旋转为/i, 10_000, rotateStart)
  const confirmStart = admin.messages.length
  admin.chat('/sg confirm')
  await admin.waitMessage(/已创建 .*机器 ID[：:]/i, 20_000, confirmStart)
  return waitUntil(() => machinesFromData().find((machine) => {
    const blocks = machine.blocks || []
    return blocks[0] === position.x && blocks[1] === position.y && blocks[2] === position.z
  }), 10_000, `${type} durable data.json record`)
}

async function removeExistingMachines(admin) {
  if (machinesFromData().length === 0) return
  const start = admin.messages.length
  admin.chat('/sg remove all')
  await admin.waitMessage(/已移除 .* 台机器/i, 30_000, start)
  await waitUntil(() => machinesFromData().length === 0, 20_000, 'machine cleanup')
}

async function connectBots(server, names) {
  const bots = []
  for (const name of names) {
    const bot = new TestBot(name)
    bots.push(bot)
    await bot.connect()
    server.command(`gamemode creative ${name}`)
    server.command(`eco set ${name} 10000`)
  }
  await delay(1_000)
  return bots
}

async function runMachineCreation(server, admin) {
  await removeExistingMachines(admin)
  const specs = [
    ['SlotMachine', new Vec3(0, 80, 0)],
    ['blackjack', new Vec3(8, 80, 0)],
    ['crash', new Vec3(16, 80, 0)],
    ['lottery', new Vec3(24, 80, 0)],
    ['poker', new Vec3(32, 80, 0)]
  ]
  const created = []
  for (const [type, position] of specs) created.push(await createMachine(server, admin, type, position))
  const data = readJson(path.join(SERVER_DIR, 'plugins', 'SmartGambling', 'data.json'))
  assert(data.dataVersion === 3, `expected dataVersion=3, got ${data.dataVersion}`)
  assert(created.length === 5 && machinesFromData().length === 5, 'five durable machines were expected')
  return Object.fromEntries(specs.map(([type, position], index) => [type, { position, data: created[index] }]))
}

function newWagersSince(before, game) {
  const ids = new Set(before.wagers.map((row) => row.id))
  return ledgerSnapshot().wagers.filter((row) => row.game === game && !ids.has(row.id))
}

function transactionsFor(wagers) {
  const wagerIds = new Set(wagers.map((row) => row.id))
  return ledgerSnapshot().transactions.filter((row) => wagerIds.has(row.wager_id))
}

function assertCleanTerminalWagers(wagers, expectedCount) {
  assert(wagers.length === expectedCount, `expected ${expectedCount} wager(s), got ${wagers.length}`)
  assert(wagers.every((row) => row.state === 'CLOSED'), `all wagers must be CLOSED: ${JSON.stringify(wagers)}`)
  const transactions = transactionsFor(wagers)
  const unsafe = transactions.filter((row) => ['PREPARED', 'CALLING', 'READY', 'UNKNOWN'].includes(row.state))
  assert(unsafe.length === 0, `unsafe transaction state(s): ${JSON.stringify(unsafe)}`)
  for (const wager of wagers) {
    const own = transactions.filter((row) => row.wager_id === wager.id)
    assert(own.filter((row) => row.purpose === 'STAKE' && row.state === 'APPLIED').length === 1,
      `wager ${wager.id} needs exactly one applied STAKE`)
    const terminal = own.filter((row) => ['LOSS', 'REFUND', 'PAYOUT'].includes(row.purpose)
      && row.state === 'APPLIED')
    assert(terminal.length === 1, `wager ${wager.id} needs exactly one terminal transaction, got ${terminal.length}`)
  }
  return transactions
}

function reelItemModel(item) {
  return item?.componentMap?.get('item_model')?.data ?? null
}

async function testForcedSlotResult(server, admin, player, machine) {
  const symbols = ['Septar', 'Septar', 'Septar', 'Septar', 'Septar']
  const testNoticePattern = /本局使用测试组合，结果不代表实际概率/
  const consumedAuditPattern = /\[SLOT TEST\].*action=consumed.*machineType=slotmachine/i
  const before = ledgerSnapshot()
  const forceStart = admin.messages.length
  admin.chat(`/sg slot test force ${player.name} SlotMachine ${symbols.join(' ')}`)
  await admin.waitMessage(
    new RegExp(`已为 ${player.name} 预设机器 slotmachine 的下一次成功下注`),
    10_000,
    forceStart
  )

  const queuedShowStart = admin.messages.length
  admin.chat(`/sg slot test show ${player.name}`)
  await admin.waitMessage(/slotmachine:\s+Septar Septar Septar Septar Septar/i, 10_000, queuedShowStart)

  await openPhysical(server, player, machine.position, 54)
  const noticeStart = player.messages.length
  await player.click(49)
  await player.waitMessage(testNoticePattern, 10_000, noticeStart)

  const wagers = await waitUntil(() => {
    const rows = newWagersSince(before, 'slot')
    return rows.length === 1 && rows[0].state === 'CLOSED' ? rows : null
  }, 20_000, 'forced slot durable settlement')
  const transactions = assertCleanTerminalWagers(wagers, 1)
  const wager = wagers[0]
  const expectedPayout = Number(wager.stake) * 50
  assert(wager.resolution_type === 'PAYOUT',
    `five forced Septar symbols must resolve as PAYOUT: ${JSON.stringify(wager)}`)
  assert(Number(wager.payout) === expectedPayout,
    `five forced Septar symbols must pay 50x stake; expected ${expectedPayout}, got ${wager.payout}`)
  const payoutTransactions = transactions.filter((row) => row.wager_id === wager.id
    && row.purpose === 'PAYOUT' && row.state === 'APPLIED')
  assert(payoutTransactions.length === 1 && Number(payoutTransactions[0].amount) === expectedPayout,
    `forced slot payout transaction must apply ${expectedPayout} exactly once: ${JSON.stringify(payoutTransactions)}`)

  await server.waitFor(
    /\[SLOT TEST\].*action=applied.*machineType=slotmachine.*symbols=Septar,Septar,Septar,Septar,Septar/i,
    10_000,
    'forced slot application audit'
  )

  const window = player.bot.currentWindow
  assert(window && window.inventoryStart === 54, 'forced slot GUI must remain open for final reel inspection')
  const middleSlots = [21, 22, 23, 24, 25]
  const middleItems = middleSlots.map((slot) => window.slots[slot])
  assert(middleItems.every(Boolean), 'all five forced-result middle slots must contain an item')
  const expectedModel = 'smartgambling:item/casino/seven'
  const itemModels = middleItems.map(reelItemModel)
  assert(itemModels.every((model) => model === expectedModel),
    `all forced Septar middle slots must expose item_model=${expectedModel}: ${JSON.stringify(itemModels)}`)
  player.record('forcedSlotMiddle', `slots=${middleSlots.join(',')} itemModels=${itemModels.join(',')}`)

  const consumedShowStart = admin.messages.length
  admin.chat(`/sg slot test show ${player.name}`)
  await admin.waitMessage(
    new RegExp(`${player.name} 当前没有待执行的老虎机测试结果`),
    10_000,
    consumedShowStart
  )

  const noticeCountBeforeNextSpin = player.messages
    .filter((entry) => testNoticePattern.test(entry.message)).length
  const consumedCountBeforeNextSpin = server.lines
    .filter((line) => consumedAuditPattern.test(line)).length
  assert(noticeCountBeforeNextSpin >= 1, 'the forced spin must emit its player test notice')
  assert(consumedCountBeforeNextSpin >= 1, 'the forced spin must emit its consumed audit')

  const beforeNextSpin = ledgerSnapshot()
  await waitUntil(() => player.bot.currentWindow?.slots[49], 5_000,
    'normal spin button after forced settlement')
  await player.click(49)
  const nextWagers = await waitUntil(() => {
    const rows = newWagersSince(beforeNextSpin, 'slot')
    return rows.length === 1 && rows[0].state === 'CLOSED' ? rows : null
  }, 20_000, 'post-force normal slot settlement')
  assertCleanTerminalWagers(nextWagers, 1)
  await delay(250)
  const noticeCountAfterNextSpin = player.messages
    .filter((entry) => testNoticePattern.test(entry.message)).length
  const consumedCountAfterNextSpin = server.lines
    .filter((line) => consumedAuditPattern.test(line)).length
  assert(noticeCountAfterNextSpin === noticeCountBeforeNextSpin,
    'the spin after one-shot consumption must not emit another test-result notice')
  assert(consumedCountAfterNextSpin === consumedCountBeforeNextSpin,
    'the spin after one-shot consumption must not consume another forced-result directive')
  await player.closeWindow()
}

async function openPhysical(server, bot, position, topSize = 54) {
  await bot.closeWindow()
  await bot.teleport(server, position.offset(0, 0, -2))
  await bot.activateBlock(position)
  return bot.waitWindow(topSize, 10_000)
}

async function choosePresetBet(bot, expectedParentSize = 54) {
  const parent = bot.bot.currentWindow
  await bot.click(46)
  const money = await bot.waitWindow(45, 5_000, parent)
  await bot.click(9)
  if (expectedParentSize === null) return null
  return bot.waitWindow(expectedParentSize, 7_500, money)
}

async function openBettingPhase(server, bot, position, command = null) {
  for (let attempt = 0; attempt < 12; attempt++) {
    await bot.closeWindow()
    if (command) {
      bot.chat(command)
      await bot.waitWindow(54, 7_500)
    } else {
      await openPhysical(server, bot, position, 54)
    }
    const current = bot.bot.currentWindow
    await bot.click(46).catch(() => {})
    try {
      return await bot.waitWindow(45, 1_250, current)
    } catch (_) {
      await bot.closeWindow()
      await delay(1_000)
    }
  }
  throw new Error(`${bot.name} could not enter a betting phase`)
}

async function testSlot(server, admin, player, contender, machine) {
  const before = ledgerSnapshot()
  await openPhysical(server, player, machine.position, 54)
  await player.click(49)
  // Three rapid ordinary clicks cover idempotency without relying on the
  // unsupported protocol double-click mode.
  await player.click(50).catch(() => {})
  await player.click(51).catch(() => {})
  await contender.teleport(server, machine.position.offset(0, 0, -2))
  await contender.activateBlock(machine.position)
  await delay(750)
  assert(!contender.bot.currentWindow, 'second player must not open an in-use slot machine')

  const reloadStart = admin.messages.length
  admin.chat('/sg reload')
  await admin.waitMessage(/已拒绝重载：当前有 .* 笔活动下注/i, 10_000, reloadStart)

  const wagers = await waitUntil(() => {
    const rows = newWagersSince(before, 'slot')
    return rows.length === 1 && rows[0].state === 'CLOSED' ? rows : null
  }, 20_000, 'slot durable settlement')
  const transactions = assertCleanTerminalWagers(wagers, 1)
  assert(transactions.filter((row) => row.purpose === 'STAKE').length === 1,
    'rapid slot clicks must withdraw exactly once')
  await player.closeWindow()
  await delay(500)

  const dataBefore = fs.readFileSync(path.join(SERVER_DIR, 'plugins', 'SmartGambling', 'data.json'), 'utf8')
  const successStart = admin.messages.length
  admin.chat('/sg reload')
  await admin.waitMessage(/配置已成功重载/i, 20_000, successStart)
  const dataAfter = fs.readFileSync(path.join(SERVER_DIR, 'plugins', 'SmartGambling', 'data.json'), 'utf8')
  assert(dataAfter === dataBefore, 'successful reload must not rewrite machine data.json')
}

async function testBlackjack(server, host, challenger, machine) {
  const before = ledgerSnapshot()
  await openPhysical(server, host, machine.position, 45)
  const hostMoney = host.bot.currentWindow
  await host.click(9)
  await waitUntil(() => newWagersSince(before, 'blackjack').length === 1,
    10_000, 'blackjack host wager')
  await waitUntil(() => !host.bot.currentWindow || host.bot.currentWindow !== hostMoney,
    5_000, 'blackjack host waiting state').catch(() => {})

  await openPhysical(server, challenger, machine.position, 27)
  await challenger.click(11)
  await waitUntil(() => newWagersSince(before, 'blackjack').length === 2,
    10_000, 'blackjack challenger wager')
  await delay(750)
  for (const player of [host, challenger]) {
    if (player.bot.currentWindow && player.bot.currentWindow.inventoryStart === 45) {
      await player.click(37).catch(() => {})
    }
  }
  const wagers = await waitUntil(() => {
    const rows = newWagersSince(before, 'blackjack')
    return rows.length === 2 && rows.every((row) => row.state === 'CLOSED') ? rows : null
  }, 20_000, 'blackjack atomic settlement')
  const transactions = assertCleanTerminalWagers(wagers, 2)
  assert(transactions.filter((row) => row.purpose === 'LOCK' && row.state === 'APPLIED').length === 2,
    'blackjack must atomically lock both wagers')
  const stakes = transactions.filter((row) => row.purpose === 'STAKE').reduce((sum, row) => sum + Number(row.amount), 0)
  const credits = transactions.filter((row) => ['REFUND', 'PAYOUT'].includes(row.purpose))
    .reduce((sum, row) => sum + Number(row.amount), 0)
  assert(stakes === 200 && credits === 200,
    `blackjack must conserve the 200 pot; stakes=${stakes}, credits=${credits}`)
  await host.closeWindow()
  await challenger.closeWindow()
}

async function testPoker(server, host, challenger, machine) {
  const before = ledgerSnapshot()
  await openPhysical(server, host, machine.position, 45)
  await host.click(9)
  await waitUntil(() => newWagersSince(before, 'poker').length === 1,
    10_000, 'poker host buy-in')

  await openPhysical(server, challenger, machine.position, 27)
  await challenger.click(11)
  await waitUntil(() => newWagersSince(before, 'poker').length === 2,
    10_000, 'poker challenger buy-in')
  await waitUntil(() => host.bot.currentWindow?.inventoryStart === 45
      && challenger.bot.currentWindow?.inventoryStart === 45,
    10_000, 'both poker table views')

  // In heads-up play the dealer/small blind (host) acts first preflop.
  await host.click(36)
  const wagers = await waitUntil(() => {
    const rows = newWagersSince(before, 'poker')
    return rows.length === 2 && rows.every((row) => row.state === 'CLOSED') ? rows : null
  }, 20_000, 'poker fold settlement')
  const transactions = assertCleanTerminalWagers(wagers, 2)
  assert(transactions.filter((row) => row.purpose === 'LOCK' && row.state === 'APPLIED').length === 2,
    'poker must atomically lock both buy-ins')
  const stakes = transactions.filter((row) => row.purpose === 'STAKE')
    .reduce((sum, row) => sum + Number(row.amount), 0)
  const credits = transactions.filter((row) => ['REFUND', 'PAYOUT'].includes(row.purpose))
    .reduce((sum, row) => sum + Number(row.amount), 0)
  assert(stakes === 200 && credits === 200,
    `poker must conserve both 100 buy-ins; stakes=${stakes}, credits=${credits}`)
  assert(wagers.every((row) => row.resolution_type === 'PAYOUT'),
    `fold settlement must return each player's remaining stack: ${JSON.stringify(wagers)}`)
  await host.closeWindow()
  await challenger.closeWindow()
}

async function testCrashRefund(server, player, machine) {
  const before = ledgerSnapshot()
  const money = await openBettingPhase(server, player, machine.position)
  await player.click(9)
  await player.waitWindow(54, 7_500, money)
  const wager = await waitUntil(() => {
    const rows = newWagersSince(before, 'crash')
    return rows.length === 1 ? rows[0] : null
  }, 10_000, 'crash wager placement')
  await player.click(46)
  const wagers = await waitUntil(() => {
    const rows = newWagersSince(before, 'crash')
    return rows.length === 1 && rows[0].state === 'CLOSED' ? rows : null
  }, 10_000, 'crash explicit refund')
  const transactions = assertCleanTerminalWagers(wagers, 1)
  assert(transactions.some((row) => row.wager_id === wager.id && row.purpose === 'REFUND'),
    'crash bet removal must durably refund the stake')
  await player.closeWindow()
}

async function testCrashRound(server, player, machine) {
  const before = ledgerSnapshot()
  const money = await openBettingPhase(server, player, machine.position)
  await player.click(9)
  await player.waitWindow(54, 7_500, money)
  await waitUntil(() => newWagersSince(before, 'crash').length === 1,
    10_000, 'crash round wager')
  await waitUntil(() => {
    const rows = newWagersSince(before, 'crash')
    return rows[0] && ['LOCKED', 'PAYOUT_LOCKED', 'SETTLING', 'CLOSED'].includes(rows[0].state)
  }, 15_000, 'crash lock')
  // Repeated ordinary clicks around the crash boundary must still produce one
  // and only one terminal resolution.
  const deadline = Date.now() + 3_000
  while (Date.now() < deadline) {
    if (player.bot.currentWindow && player.bot.currentWindow.inventoryStart === 54) {
      await player.click(4).catch(() => {})
    }
    const row = newWagersSince(before, 'crash')[0]
    if (row && row.state === 'CLOSED') break
    await delay(100)
  }
  const wagers = await waitUntil(() => {
    const rows = newWagersSince(before, 'crash')
    return rows.length === 1 && rows[0].state === 'CLOSED' ? rows : null
  }, 20_000, 'crash payout-or-loss race settlement')
  const transactions = assertCleanTerminalWagers(wagers, 1)
  assert(transactions.filter((row) => ['PAYOUT', 'LOSS'].includes(row.purpose)).length === 1,
    'crash cashout race must choose exactly payout or loss')
  await player.closeWindow()
}

async function testJackpot(players) {
  const before = ledgerSnapshot()
  // Mineflayer waits for a click transaction acknowledgement that can lag
  // behind a server-opened replacement window.  Prepare all three selectors
  // first, then send their ordinary slot-9 clicks concurrently so one bot's
  // acknowledgement cannot consume the whole global betting phase.
  await Promise.all(players.map((player) => openBettingPhase(null, player, null, '/jackpot')))
  await Promise.all(players.map((player) => player.click(9)))
  await waitUntil(() => newWagersSince(before, 'jackpot').length === players.length,
    10_000, 'all jackpot tickets')
  const wagers = await waitUntil(() => {
    const rows = newWagersSince(before, 'jackpot')
    return rows.length === players.length && rows.every((row) => row.state === 'CLOSED') ? rows : null
  }, 30_000, 'jackpot atomic winner settlement')
  const transactions = assertCleanTerminalWagers(wagers, players.length)
  assert(transactions.filter((row) => row.purpose === 'LOCK' && row.state === 'APPLIED').length === players.length,
    'jackpot must lock every ticket')
  const payout = transactions.filter((row) => row.purpose === 'PAYOUT' && row.state === 'APPLIED')
  const losses = transactions.filter((row) => row.purpose === 'LOSS' && row.state === 'APPLIED')
  assert(payout.length === 1 && losses.length === players.length - 1,
    'jackpot must have exactly one winner and all other tickets loss')
  assert(Number(payout[0].amount) === players.length * 100,
    `jackpot payout must equal total pot ${players.length * 100}`)
  for (const player of players) await player.closeWindow()
}

async function testPersistenceRestart(server, admin, beforeText, beforeMachines) {
  const afterStartMachines = machinesFromData()
  assert(beforeMachines.length === 5 && afterStartMachines.length === 5,
    'restart must preserve all five machines')
  assert(JSON.stringify(machineIdentity(afterStartMachines)) === JSON.stringify(machineIdentity(beforeMachines)),
    'restart must preserve machine IDs, roles, and entity UUIDs')

  for (const machine of afterStartMachines) {
    const position = new Vec3(machine.blocks[0], machine.blocks[1], machine.blocks[2])
    await admin.teleport(server, position.offset(0, 0, -2))
    const expected = new Set((machine.entities || []).map((entity) => String(entity.uuid).toLowerCase()))
    await waitUntil(() => {
      const visible = new Set(Object.values(admin.bot.entities)
        .filter((entity) => entity && entity.uuid)
        .map((entity) => String(entity.uuid).toLowerCase()))
      return [...expected].every((uuid) => visible.has(uuid))
    }, 10_000, `${machine.type} persisted ArmorStand UUIDs`)
  }

  const slot = afterStartMachines.find((machine) => machine.type === 'SlotMachine')
  const slotPosition = new Vec3(slot.blocks[0], slot.blocks[1], slot.blocks[2])
  await openPhysical(server, admin, slotPosition, 54)
  await admin.closeWindow()
  await delay(500)

  const afterText = fs.readFileSync(path.join(SERVER_DIR, 'plugins', 'SmartGambling', 'data.json'), 'utf8')
  assert(afterText === beforeText, 'loading and opening persisted machines must not rewrite data.json')
}

async function testHardKillRecovery(server, player, slotMachine) {
  const before = ledgerSnapshot()
  server.command('save-all flush')
  await server.waitFor(/Saved the game/i, 60_000, 'pre-crash provider/world flush')
  await delay(500)

  const position = new Vec3(slotMachine.blocks[0], slotMachine.blocks[1], slotMachine.blocks[2])
  await openPhysical(server, player, position, 54)
  await player.click(49)
  const wager = await waitUntil(() => {
    const rows = newWagersSince(before, 'slot')
    if (rows.length !== 1) return null
    const stake = transactionsFor(rows).find((row) => row.purpose === 'STAKE' && row.state === 'APPLIED')
    return rows[0].state === 'OPEN' && stake ? rows[0] : null
  }, 5_000, 'durable OPEN slot wager before hard kill', 25)

  await server.hardKill()
  await server.start()
  await player.connect()

  const recovered = await waitUntil(() => {
    const row = ledgerSnapshot().wagers.find((candidate) => candidate.id === wager.id)
    return row && row.state === 'CLOSED' ? row : null
  }, 30_000, 'hard-kill wager recovery')
  assert(recovered.resolution_type === 'REFUND' && Number(recovered.payout) === 100,
    `hard-killed OPEN slot wager must refund 100, got ${JSON.stringify(recovered)}`)
  const ownTransactions = ledgerSnapshot().transactions.filter((row) => row.wager_id === wager.id)
  assert(ownTransactions.filter((row) => row.purpose === 'STAKE' && row.state === 'APPLIED').length === 1,
    'hard-kill recovery must retain exactly one applied stake')
  assert(ownTransactions.filter((row) => row.purpose === 'REFUND' && row.state === 'APPLIED').length === 1,
    'hard-kill recovery must apply exactly one refund')
  assert(ownTransactions.every((row) => !['PREPARED', 'CALLING', 'READY', 'UNKNOWN'].includes(row.state)),
    'hard-kill recovery must not leave an unsafe transaction state')

  const messageStart = player.messages.length
  player.chat('/balance')
  const balanceMessage = await player.waitMessage(/(?:balance|余额).*[\d,.]+/i, 10_000, messageStart)
  const amounts = balanceMessage.message.replaceAll(',', '').match(/-?\d+(?:\.\d+)?/g) || []
  const balance = Number(amounts.at(-1))
  assert(Math.abs(balance - 10_000) < 0.000001,
    `provider balance must return to 10000 after recovery, got ${balanceMessage.message}`)

  const transactionIds = ownTransactions.map((row) => row.id).sort()
  await server.stop(false)
  await server.start()
  await player.connect()
  const afterSecondStart = ledgerSnapshot()
  const wagerAfterSecondStart = afterSecondStart.wagers.find((row) => row.id === wager.id)
  const transactionIdsAfterSecondStart = afterSecondStart.transactions
    .filter((row) => row.wager_id === wager.id)
    .map((row) => row.id)
    .sort()
  assert(wagerAfterSecondStart?.state === 'CLOSED'
      && wagerAfterSecondStart.resolution_type === 'REFUND',
    'a second restart must keep the recovered wager closed')
  assert.deepEqual(transactionIdsAfterSecondStart, transactionIds,
    'a second restart must not create or replay another recovery transaction')

  const secondMessageStart = player.messages.length
  player.chat('/balance')
  const secondBalanceMessage = await player.waitMessage(/(?:balance|浣欓).*?[\d,.]+/i,
    10_000, secondMessageStart)
  const secondAmounts = secondBalanceMessage.message.replaceAll(',', '').match(/-?\d+(?:\.\d+)?/g) || []
  const secondBalance = Number(secondAmounts.at(-1))
  assert(Math.abs(secondBalance - 10_000) < 0.000001,
    `provider balance must remain 10000 after a second restart, got ${secondBalanceMessage.message}`)
}

async function runCase(report, name, action) {
  const startedAt = Date.now()
  try {
    await action()
    report.push({ name, status: 'PASS', durationMs: Date.now() - startedAt })
  } catch (error) {
    report.push({ name, status: 'FAIL', durationMs: Date.now() - startedAt, error: error.stack || String(error) })
    throw error
  }
}

async function main() {
  const server = new PaperServer()
  const bots = []
  const report = []
  const persistenceMode = process.argv.includes('--persistence-only')
  const hardKillMode = process.argv.includes('--hard-kill-recovery')
  const creationGuideMode = process.argv.includes('--creation-guide-only')
  const bootstrapMode = process.argv.includes('--bootstrap-only')
  const createOnlyMode = process.argv.includes('--create-only')
  const forcedSlotIntegrationMode = !persistenceMode && !hardKillMode && !creationGuideMode
    && !bootstrapMode && !createOnlyMode
  const dataFile = path.join(SERVER_DIR, 'plugins', 'SmartGambling', 'data.json')
  const beforePersistenceText = persistenceMode && fs.existsSync(dataFile)
    ? fs.readFileSync(dataFile, 'utf8') : null
  const beforePersistenceMachines = persistenceMode ? machinesFromData() : []
  let forcedSlotConfigGuard
  let failure
  try {
    // Existing isolated servers may predate the Testing section. Always stage
    // an explicit value before startup: only the full normal suite enables it.
    forcedSlotConfigGuard = isolateForcedSlotConfig(forcedSlotIntegrationMode)
    await server.start()
    if (forcedSlotIntegrationMode) {
      // A brand-new server creates config.yml during the first startup. Patch
      // that generated file and restart once; /sg reload is player-only.
      // Existing servers were patched before startup and already emitted this
      // warning.
      if (!forcedSlotConfigGuard) {
        forcedSlotConfigGuard = isolateForcedSlotConfig(true)
        assert(forcedSlotConfigGuard, 'SmartGambling did not create config.yml during startup')
        await server.stop(false)
        await server.start()
      }
      await server.waitFor(/Forced slot results are ENABLED/i, 30_000,
        'isolated forced slot test mode activation')
    }
    await bootstrap(server)
    if (bootstrapMode) return

    if (creationGuideMode) {
      const [admin] = await connectBots(server, ['SGGuideAdmin'])
      bots.push(admin)
      server.command(`op ${admin.name}`)
      await delay(1_000)
      const position = new Vec3(40, 80, 0)
      await runCase(report, '创建向导：中文 BossBar、选择棒、旋转、事务创建与撤销', async () => {
        const leftover = machinesFromData().find((machine) => {
          const blocks = machine.blocks || []
          return blocks[0] === position.x && blocks[1] === position.y && blocks[2] === position.z
        })
        if (leftover) {
          const cleanupStart = admin.messages.length
          admin.chat(`/sg remove ${leftover.id}`)
          await admin.waitMessage(/已移除\s+1\s+台机器/i, 20_000, cleanupStart)
          await waitUntil(() => !machinesFromData().some((machine) => machine.id === leftover.id),
            10_000, '清理上次中断留下的测试机器')
        }
        const created = await createMachine(server, admin, 'SlotMachine', position)
        assert(created.direction === 'EAST', `旋转后应为 EAST，实际为 ${created.direction}`)
        assert((created.entities || []).length === 2, '老虎机应自动生成座位和模型两个实体')
        const undoStart = admin.messages.length
        admin.chat('/sg undo')
        await admin.waitMessage(/已安全移除本次创建的上一台机器/i, 20_000, undoStart)
        await waitUntil(() => !machinesFromData().some((machine) => machine.id === created.id),
          10_000, '撤销后的持久数据删除')
      })
      return
    }

    if (persistenceMode) {
      const [admin] = await connectBots(server, ['SGBotAdmin'])
      bots.push(admin)
      server.command(`op ${admin.name}`)
      await delay(1_000)
      await runCase(report, 'data v3: clean restart preserves cross-chunk entity UUIDs and snapshot bytes',
        () => testPersistenceRestart(server, admin, beforePersistenceText, beforePersistenceMachines))
      return
    }

    if (hardKillMode) {
      // Use a fresh offline UUID so an interrupted earlier run cannot leave the
      // recovery actor mounted on a persisted machine seat.
      const recoveryName = `SGR${Date.now().toString(36).slice(-10)}`.slice(0, 16)
      const [player] = await connectBots(server, [recoveryName])
      bots.push(player)
      const slotMachine = machinesFromData().find((machine) => machine.type === 'SlotMachine')
      assert(slotMachine, 'hard-kill recovery requires the persisted slot machine')
      await runCase(report, 'ledger recovery: SIGKILL during durable OPEN slot wager refunds exactly once',
        () => testHardKillRecovery(server, player, slotMachine))
      return
    }

    const connected = await connectBots(server, ['SGBotAdmin', 'SGBotA', 'SGBotB', 'SGBotC'])
    bots.push(...connected)
    const [admin] = connected
    server.command(`op ${admin.name}`)
    await delay(1_000)
    admin.chat('/sg ledger list 1')
    await admin.waitMessage(/SmartGambling 资金账本第/i, 10_000)
    await prepareArena(server, connected)
    const machines = await runMachineCreation(server, admin)
    fs.writeFileSync(
      path.join(ARTIFACT_DIR, 'machines.json'),
      `${JSON.stringify(machinesFromData(), null, 2)}\n`,
      'utf8'
    )
    if (createOnlyMode) return

    const [, botA, botB, botC] = connected
    await runCase(report, 'slot test mode: forced five-Septar result, 50x ledger payout and one-shot consume',
      () => testForcedSlotResult(server, admin, botA, machines.SlotMachine))
    await runCase(report, 'slot: CE GUI, rapid-click idempotency, in-use guard, reload refusal/success',
      () => testSlot(server, admin, botA, botB, machines.SlotMachine))
    await runCase(report, 'blackjack: two-player equal stake, atomic lock and settlement',
      () => testBlackjack(server, botB, botC, machines.blackjack))
    await runCase(report, 'poker: heads-up equal buy-ins, blinds, fold and conserved settlement',
      () => testPoker(server, botB, botC, machines.poker))
    await runCase(report, 'crash: explicit bet removal refunds durably',
      () => testCrashRefund(server, botA, machines.crash))
    await runCase(report, 'crash: cashout-vs-crash rapid-click race resolves exactly once',
      () => testCrashRound(server, botB, machines.crash))
    await runCase(report, 'jackpot: three-player atomic lock, one winner, conserved pot',
      () => testJackpot([botA, botB, botC]))

    const finalLedger = ledgerSnapshot()
    assert(finalLedger.wagers.every((row) => row.state === 'CLOSED'), 'all gameplay wagers must be closed')
    assert(finalLedger.transactions.every((row) => !['PREPARED', 'CALLING', 'READY', 'UNKNOWN'].includes(row.state)),
      'normal gameplay must leave no unsafe transaction state')
  } catch (error) {
    failure = error
    fs.writeFileSync(path.join(ARTIFACT_DIR, 'failure.txt'), `${error.stack || error}\n`, 'utf8')
    process.stderr.write(`${error.stack || error}\n`)
  } finally {
    try {
      fs.writeFileSync(path.join(ARTIFACT_DIR, 'report.json'), `${JSON.stringify({
        startedAt: timestamp,
        cases: report,
        ledger: ledgerSnapshot(),
        machines: machinesFromData()
      }, null, 2)}\n`, 'utf8')
    } catch (error) {
      process.stderr.write(`Could not write report: ${error.stack || error}\n`)
    }
    for (const bot of bots) {
      try { await bot.disconnect() } catch (error) { process.stderr.write(`${error.stack || error}\n`) }
    }
    try { await server.stop() } catch (error) { process.stderr.write(`${error.stack || error}\n`) }
    try {
      forcedSlotConfigGuard?.restore()
    } catch (error) {
      process.stderr.write(`Could not restore SmartGambling config: ${error.stack || error}\n`)
    }
  }
  if (failure) process.exitCode = 1
}

main()
