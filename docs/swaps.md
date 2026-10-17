# Swaps

How Serotine swaps one token for another without a server it has to trust:
on Uniswap's contracts directly, and through CoW Protocol. This page is a
design, and nothing on it is built yet. Issue #69 tracks it. A swap PR is held
to this page together with docs/wallet-ux.md (the screens and their rules),
docs/embedding-myotis.md (what the engine reads and sends) and
docs/keys-and-signing.md (what is signed). Where this page and CLAUDE.md
disagree, CLAUDE.md wins and this page is fixed.

## The owner's direction (2026-10-18)

- **Uniswap first, CoW Protocol second.** Uniswap is built first: it needs
  nothing but the chain, which the engine already proves.
- **Once CoW is built, a swap goes to CoW while CoW's servers work**, with
  Uniswap as the fallback.
- **An API change must not break swaps.** CoW's HTTP API can change, and the
  app would then need an update. *How often CoW's API changes*, below,
  measures that and sets out how the design limits what it costs.

## The two venues

| | Uniswap, directly | CoW Protocol |
|---|---|---|
| Who has to be up | Ethereum peers | CoW's order API, its auction and at least one solver CoW DAO has allow-listed |
| Who can refuse a swap | No one: the contracts are open to every caller | The API (it keeps a deny-list of accounts) and the solvers (none has to fill) |
| The quote | A verified call: QuoterV2 run in the engine's local EVM over proven state | CoW's own estimate, net of its fee, which the light client cannot prove |
| What enforces the signed minimum | The router: `amountOutMinimum`, or the call reverts | The settlement contract: the order's `buyAmount`, or it does not settle |
| MEV | The transaction is gossiped to the public mempool, so a sandwich can take up to the slippage tolerance | A batch auction: the user's order never sits in the public mempool |
| Gas | The account pays it in ETH | Taken out of the price; ETH only for an approval |
| What leaves the device | The signed transaction, to peers, like every send | The order, the account's address and the device's IP address, to `api.cow.fi` |
| On Sepolia | v3 and v4 deployed | Deployed; the API is `https://api.cow.fi/sepolia/api/v1` |

Uniswap is the venue that needs no server. CoW is open to anyone but runs on
CoW's servers. Its trust is bounded: the settlement contract checks the
signature and the limit, so the API and the solvers can delay an order or
leave it unfilled, but they can never fill it at a worse rate than the one
signed.

## What both venues share

- **Amounts are token units** (docs/wallet-ux.md, *Rules every screen
  follows*). The user enters the amount to sell in the token's units.
  - The signed minimum never comes from a Chainlink price.
    - On Uniswap it is the pool's own verified quote for this exact amount,
      less the tolerance. That is the same kind of value as Send's verified
      simulation.
    - On CoW it is never below a floor made of that verified quote (*The
      signed minimum*); CoW's estimate can only raise it.
  - So the ruling that a price never reaches a signature (CLAUDE.md, *Data
    sources*) stands unchanged.
  - Fiat values stay hints beside the amounts.
- **Only tokens the wallet knows.** These are the built-in tokens, the ones
  the user added (marked as the contract's claim, as everywhere), native ETH
  and the network's pinned WETH. A swap never offers a token by an address
  the wallet has not listed.
- **The proceeds go to the account itself.** No other recipient is offered:
  sending somewhere else is Send's job.
- **Slippage.** The tolerance applied to a verified quote, rounded down. The
  default and the bounds are the owner's (*Open questions*).
- **Approvals are their own transaction, for the exact amount.**
  - When the account's allowance for the venue's spender does not cover the
    amount, the swap review starts with an approval step: "Allow
    ‹SwapRouter02› to take 100 USDC". The spender is pinned and shown with its
    name, blockie and full address.
  - The approval is quoted, signed and sent as any transaction from the
    account is. The swap is quoted only once its verified receipt is in.
  - An allowance that already covers the amount is used and said. A token
    whose `approve` reverts from one non-zero value to another (USDT's kind)
    shows that in the approval's verified run, and the step then approves
    zero first.
  - An allowance left over by a swap that never ran moves nothing on its
    own:
    - SwapRouter02 pulls only from the account that calls it.
    - CoW's vault relayer pulls only for an order the account signed.
  - Unlimited approvals and Permit2 are the owner's decision (*Open
    questions*).
- **Freshness, for a swap from an account held here**, as Send's quote
  (docs/wallet-ux.md, screen 8):
  - A quote is made when the review is entered, again on Refresh, and when
    the screen is shown again after it was not. It is made again at the tap
    that would sign it when it is over a minute old.
  - At the tap that signs a Uniswap swap, the exact transaction is run again
    at the current head. When it still succeeds, what the user saw is what is
    signed. When it does not (the minimum no longer met), it is quoted again
    and shown, not signed.
  - At the tap that signs a CoW order, the verified floor is quoted again.
    When it has risen above the minimum shown, the order is made again and
    shown, not signed.
  - A Safe's swap has its own rules (*A Safe's swap*).
- **The record.** `SentAction` gains an approval and a swap: the venue, the
  token sold and its amount, the token bought and the signed minimum. A
  signed CoW order is recorded as soon as it is signed (*A signed order is
  live until it is proven dead*).
  - The status screen (9) says "Swap of 100 USDC for at least 4.2 LINK" and,
    once known, the amount received.
  - On Uniswap that amount comes from the verified receipt: the token's
    `Transfer` log to the account. For ETH out, it is WETH's `Withdrawal` log
    from the router, since unwrapped ETH comes with no `Transfer` log.
  - On CoW it comes from the settlement's `Trade` log (*An order, step by
    step*). While that log cannot be read, the status says "at least" the
    minimum.
- **Native ETH** goes in and out through WETH, as each venue does it (below).

## A Safe's swap

A Safe swaps through the co-signing queue (docs/wallet-ux.md, *Co-signing a
Safe transaction*), on Uniswap only until CoW's pre-signature is built
(*Later on CoW*).

- **Two transactions.** The approval and the swap are two Safe transactions
  at consecutive nonces. A MultiSend batch would be a delegate call, which
  this wallet refuses (issue #57).
- **Decoded here.** Screen 17 decodes each from its raw call to a pinned
  contract, never from a description: "Swap 100 USDC for at least 4.2 LINK on
  Uniswap".
- **The minimum and the deadline are fixed when the swap is proposed.**
  - Re-quoting at a later owner's tap would change the `SafeTx` hash and void
    the signatures already collected, so it is not done.
  - The deadline is longer than an account's ten minutes, since collecting
    signatures by QR code takes time: 24 hours proposed, the owner's decision
    (*Open questions*).
- **Each owner sees the current state**: the minimum signed, and what the
  swap would return now in a verified run, said as met or not met.
- **Execute runs only while the swap still succeeds.** Its verified run must
  succeed: deadline not passed, minimum met.
  - The swap's run needs the approval executed first, so until then it says
    "Not simulated".
  - A swap whose run fails is not executed. The owners replace it at its
    nonce (a new proposal, or a rejection), as for any Safe transaction.

## Uniswap (built first)

### Pins

The deployment table on Uniswap's developer docs gives these addresses on
Sepolia (read 2026-10-18):

| contract | Sepolia |
|---|---|
| `UniswapV3Factory` | `0x0227628f3F023bb0B980b67D528571c95c6DaC1c` |
| `QuoterV2` | `0xEd1f6473345F45b75F8179591dd5bA1888cf2FB3` |
| `SwapRouter02` | `0x3bFA4769FB09eefC5a80d6E87c3B9C650f7Ae48E` |
| `WETH9` | `0xfff9976782d46cc05630d1f6ebab18b2324d6b14` |

They are pinned per `Network`, as Safe's deployments are (`SafeDeployments`):

- The PR that adds a pin also pins the runtime code hash it reads through the
  engine (`getCode` is verified against the account's proven code hash).
- A pinned address whose code hash differs is no venue, and the swap screen
  says why.
- A pool is a pool only when the pinned factory's `getPool(tokenA, tokenB,
  fee)` returns it in a verified call. An address from anywhere else is never
  treated as a pool.

### Finding a route

- **Candidates.**
  - The direct pool at each fee tier (0.01 %, 0.05 %, 0.3 %, 1 %).
  - One hop through WETH: tokenIn → WETH → tokenOut, with the tier of each
    leg that has the most liquidity in range (`liquidity()`).
  - A pool with no liquidity in range is skipped.
- **Each candidate is quoted** with a verified call:
  - `quoteExactInputSingle` for a single pool;
  - `quoteExactInput` with the encoded path (token ‖ fee ‖ token ‖ fee ‖
    token) for one hop.
- **The largest amount out wins**; on a tie, the route with fewer hops.
- **Uniswap's routing API is not used**: it is an off-chain service, and its
  answer would be a claim the light client cannot prove. Split routes and
  routes of more than one hop are what this gives up.
- **The cost.** A quote reads every storage slot the swap touches: the pool's
  state, the tick bitmap words and each initialized tick it crosses. The
  engine proves each slot from peers.
  - Part 2 measures this on Sepolia and bounds the number of candidates
    quoted per review if it has to.
  - Part 2 also finds out which built-in tokens have pools on Sepolia at all.
- **Exact input only.** The user says how much to sell. Exact output ("buy
  exactly 5 LINK") can come later.

### The transaction

- **One call:** `SwapRouter02.multicall(deadline, data)`, where `data` holds
  `exactInputSingle` (or `exactInput`) with the recipient, `amountIn`,
  `amountOutMinimum` (the signed minimum) and no price limit.
  - **The deadline**, for an account held here, is the verified head's
    timestamp plus ten minutes, never the device's clock (as the price rules
    measure age: docs/wallet-ux.md, *Prices*). A Safe's is longer (*A Safe's
    swap*).
  - **Selling ETH:** the token in is WETH9 and the transaction's value is
    `amountIn`; the router wraps it.
  - **Buying ETH:** the recipient is the router itself (`ADDRESS_THIS`), and
    `unwrapWETH9(amountOutMinimum, account)` follows in the same multicall.
- **Quoted and sent like any transaction from a held account**: through
  `CallQuoter` and `signAndSend`, with the same nonce record, fee levels,
  estimate, verified run and receipt checks (docs/embedding-myotis.md,
  *Sending*).
- **The review** (screen 23) shows:
  - the amount sold, exact;
  - the amount the verified run returns ("you receive 4.31 LINK, run at block
    N") and the signed minimum ("at least 4.29 LINK, 0.5 % tolerance");
  - the route: its pools with their fee tiers and blockies;
  - the price impact: the quote against the pool's spot price (`slot0`),
    chain data both;
  - the most the fee can be, and the balances of both tokens and of ETH
    before and after.
- **A hint, display-only.** The pool's spot price can be moved within a
  block just as the quote can. When the quote's rate is far from the
  Chainlink values the screen already shows, the review says so. On Sepolia
  the testnet feeds and the test pools do not track each other, so there the
  hint says that and no more. It never changes an amount.
- **MEV.** The transaction sits in the public mempool, where it can be
  sandwiched by up to the tolerance.
  - On Sepolia this does not matter. On mainnet, once it is allowed, the
    review says so.
  - A private relay (Flashbots Protect, MEV Blocker) is an HTTP endpoint no
    ruling allows, so none is used.

### Later on Uniswap

- **v4** keeps every pool in one `PoolManager`, quotes through `V4Quoter` and
  swaps through the Universal Router with Permit2.
  - A v4 pool can carry a hook, arbitrary code that runs with every swap in
    it. Whether only pools without hooks are used, or pinned hooks too, is
    the owner's decision.
  - Permit2 brings EIP-712 permits and a standing approval of the Permit2
    contract.
- **v2 pools**, for a token that trades only there.

## CoW Protocol (built second, preferred once built)

### An order, step by step

1. **The quote.** `POST /api/v1/quote` with the tokens, the account, `kind:
   sell` and `sellAmountBeforeFee`, the amount the user sells.
   - The answer's `buyAmount` is CoW's estimate, net of the network fee CoW
     takes out of the trade.
   - It is shown as "CoW's quote (not verified)".
2. **The order is built here**, never taken from the API. Its fields:
   - `sellToken`, `buyToken`, `sellAmount` (the whole amount the user
     sells);
   - `receiver`: the account;
   - `buyAmount`: the signed minimum (*The signed minimum*, below);
   - `validTo`: the verified head's timestamp plus a few minutes (*Open
     questions*);
   - `appData`: the hash of a pinned app-data document;
   - `feeAmount`: 0 (the API's spec, `OrderParameters`: "When creating an
     order, this should be set to zero");
   - `kind`: sell; `partiallyFillable`: false;
   - `sellTokenBalance` and `buyTokenBalance`: `erc20`.
3. **The digest** is GPv2's EIP-712 hash of that order under the domain
   `{name: "Gnosis Protocol", version: "v2", chainId, verifyingContract:
   GPv2Settlement}`.
   - The app computes the domain separator itself. Before anything is signed,
     it checks that value against the settlement contract's own
     `domainSeparator()`, read in a verified call.
   - The `Signer` port signs the digest as it is. To the signer an order is
     one more digest the domain computes, as the `SafeTx` hash is.
4. **The submission.**
   - The order's UID is computed here: digest ‖ owner ‖ `validTo`.
   - The order, its UID and its signature are recorded before anything is
     sent.
   - `POST /api/v1/orders` sends the order, its signature
     (`signingScheme: eip712`) and the full app-data document.
   - The UID the API returns must be the one computed here. Any other UID is
     a failed submission.
   - Whatever the POST returns, the order is now live (*A signed order is
     live until it is proven dead*).
5. **The fill is read on chain, never from the API.**
   - The settlement contract records an order's fill in
     `filledAmount(uid)`: zero, the sell amount once filled, or the maximum
     `uint256` once invalidated. That value alone does not settle every case:
     - `freeFilledAmountStorage` lets a settlement reset it to zero once
       `validTo` has passed;
     - `invalidateOrder` writes the maximum whether the order filled before
       or not.

     So the fill is read at a block where neither can have happened (*A
     signed order is live until it is proven dead*).
   - How much was bought is in the settlement's `Trade` log, found in the
     verified receipts of the block that filled it (`getBlockReceipts`). That
     block is found by reading `filledAmount` at the blocks of the order's
     window. `getLogs` needs a log index that the engine does not keep by
     default (`VerifiedReads.getLogs`), so it is not relied on.
   - The API's order status is shown as CoW's report only.
6. **The approval** is of the pinned `GPv2VaultRelayer` for the exact sell
   amount, its own transaction as above, mined before the order is signed.

CoW's contracts have the same address on every network CoW runs on. The
settlement contract is not upgradeable and has been the same since 2021.

| contract | address |
|---|---|
| `GPv2Settlement` | `0x9008D19f58AAbD9eD0D60971565AA8510560ab41` |
| `GPv2VaultRelayer` | `0xC92E8bdf79f0507f65a392b0ab4667716BFE0110` |

They are pinned with their code hashes, as Uniswap's are.

### The signed minimum

CoW's estimate is net of its fee. Uniswap's quote is not: there the account
pays the gas in ETH on top. So the two are compared net.

- **The Uniswap net** is the verified Uniswap quote less that swap's expected
  network fee (its estimate × the Normal level's fee), converted into the
  bought token by a verified quote of that much WETH. That conversion is a
  pool's answer for an exact amount, not a price.
  - When the bought token is ETH or WETH, nothing is converted.
  - When there is no route to convert through, the Uniswap net is the quote
    itself. CoW is then held to the stricter floor, and a small swap goes to
    Uniswap.
- **The floor** is the Uniswap net × (1 − tolerance).
- **CoW is used when its estimate reaches the floor.** The order's
  `buyAmount` is then max(CoW's estimate × (1 − tolerance), the floor).
  - CoW's servers can therefore make an order fail to fill, but never fill
    it below the floor.
  - An estimate below the floor means that, by CoW's own account, Uniswap
    gives more. The swap then goes to Uniswap at once, rather than through
    an order that waits out its validity.
- **A pair with no Uniswap route** has no floor, only CoW's estimate.
  Whether to offer it signed on that estimate alone, with a warning, or not
  to offer it on CoW at all, is the owner's decision (*Open questions*).
  Until decided, such a pair is not offered.

### CoW first, Uniswap as the fallback

The venue is chosen for each swap when its review is made.

- **Before anything is signed**, the swap goes to Uniswap when CoW's quote:
  - does not come within 10 seconds;
  - comes back as an HTTP error;
  - cannot be decoded;
  - is refused (no liquidity, an unsupported token, the account
    deny-listed);
  - or falls below the floor.

  The swap also goes to Uniswap when the account sells native ETH, until
  CoW's ETH-flow contract is pinned (*Later on CoW*).

  The review says which venue it uses and why: "CoW did not answer, so this
  swap uses Uniswap".

### A signed order is live until it is proven dead

Once a signature exists, anyone who holds it can submit the order until
`validTo`. That includes CoW after a POST that timed out, failed with a 5xx,
or returned another UID. A fallback that runs while the order can still fill
could swap the same tokens twice.

- **Only an order that provably never left the device is dropped at once.**
  That is a POST that never connected (no DNS answer, connection refused); its
  signature is then discarded. Every other order is live, whatever the POST
  returned.
- **A live order is kept across restarts.** It sits in a record of its own
  beside `sent-transfers.json`: the order, its UID, its signature, the account
  and how far its proof has come.
- **Send and a new swap hold back what a live order may still take** of the
  sold token, as they do for a transfer not mined yet
  (docs/embedding-myotis.md, *Sending*).
- **An order is proven dead in one of two ways, each decided only at a
  finalized block**, so that no reorganization can bring a fill back:
  - *Expired.* Take the last block whose timestamp is at or below `validTo`.
    `filledAmount(uid)` is read at that block once it is finalized. Zero
    proves the order never filled: no settlement can fill it after
    `validTo`, and none can free its storage up to that block.
  - *Invalidated.* The account calls `invalidateOrder(uid)`, a transaction,
    to stop waiting. Then:
    - `filledAmount(uid)` is read at the parent of the invalidation's block;
    - the invalidation block's verified receipts are searched for a `Trade`
      with the UID, a settlement earlier in the same block.

    Zero and no such `Trade` prove the order never filled. Both reads wait
    until that block is finalized.
- **An off-chain cancellation through the API proves nothing**: a solver may
  already hold the order. It is not built.
- **The outcomes.**
  - *Filled* ends the swap, read on chain as above, whatever the API says.
  - *Proven dead and unfilled* opens the Uniswap review for the same swap.
  - *Not provable* is said as such, and no fallback is offered: for example,
    a past block outside the window the engine serves after the app was
    closed for long. The balance shows what happened, and a new swap is the
    user's own decision.
- **The wait.** On Sepolia a block is final about 13 minutes after it is
  made. A fallback after a posted order therefore waits for `validTo` plus
  finality, and the status says so. Whether a margin of a few blocks may
  stand in for finality is the owner's decision (*Open questions*).

### What CoW learns, and how the app talks to it

- **What CoW learns.** Over HTTPS to `api.cow.fi`: the account's address, the
  pair, the amounts and the device's IP address. This is the content of the
  *Data sources* ruling that lands before CoW's code (CLAUDE.md). Keeping the
  IP address from CoW (Tor, for one) is a question of its own.
- **No API key.** The base URL is pinned per `Network`.
- **Ktor** is the HTTP client (CLAUDE.md, *Platform & language direction*).
  It is the app's first, so its engine on each platform is part 6's
  dependency question.
- **The calls:**
  - `POST /api/v1/quote`;
  - `POST /api/v1/orders`, with the full app-data document, so no separate
    `PUT /api/v1/app_data` is needed;
  - `GET /api/v1/orders/{UID}`, for CoW's report.

  An off-chain cancellation (`DELETE /api/v1/orders`) would need a second
  EIP-712 signature (`OrderCancellations`) and proves nothing (above), so it
  is not used.

### How often CoW's API changes

The owner's question. The evidence is the history of
`crates/orderbook/openapi.yml` in `cowprotocol/services`, the spec the API is
served from, read 2026-10-18.

- **146 commits since 2021-11:** 3 in 2021, 32 in 2022, 26 in 2023, 31 in
  2024, 14 in 2025, and 40 in 2026 up to 2026-10-16. Most add endpoints,
  networks or fields.
- **No version to rely on.** The paths have been `/api/v1` since 2021 (only
  trades and the solver competition moved to `/api/v2`), the spec's version
  reads `0.0.1`, and changes land in place.
- **CoW usually deprecates first and removes later:**
  - `OrderClass` is deprecated and still returned, always `limit`
    (2026-09).
  - `/api/v1/trades` was deprecated when `/api/v2/trades` came (2025-12) and
    removed on 2026-10-16.
- **What would have broken a client that uses only the calls above:**
  - the app-data document (2023), after which the API must know the document
    an order's `appData` hashes;
  - the fee moving into the price (2024), after which an order is signed with
    `feeAmount` 0.

  That is about one change a year. Response fields have been removed too
  (`full_fee_amount` and `solver_fee`, 2025-01; `executed_surplus_fee`,
  2025-02), which breaks only a client that insists on them.
- **What cannot change** without a new settlement contract: the order's
  format, its EIP-712 domain and what a signature over it means.

So an API change can need an app update. The design keeps that to the CoW
route being unavailable, never to a wrong swap:

1. **Everything that matters is built and checked here:**
   - the order and its UID;
   - the domain separator, against the chain;
   - the floor, from verified quotes;
   - the fill, on chain at a finalized block.

   The API delivers the order and offers an estimate no one trusts.
2. **Lenient decoding of the few fields used.** Unknown fields are ignored,
   and only the fields the app reads are required. Requests carry only
   required fields.
3. **A quote that does not decode or check means CoW is unavailable**, and
   the swap goes to Uniswap before anything is signed.
   - A change shows up as "CoW gave an answer this version does not
     understand", not as silence. The next app update brings CoW back.
   - A change that breaks the POST after signing costs a wait for the order
     to be proven dead, never a second swap.
4. **A network test, `@Ignore`d by default** (CLAUDE.md, *Conventions*),
   quotes against `api.cow.fi/sepolia` and decodes the answer. Whether a
   scheduled workflow runs it and watches `openapi.yml` for changes to the
   calls above is the owner's call.

### Later on CoW

- **A Safe on CoW** signs with a pre-signature: Safe transactions approve
  the vault relayer and call `setPreSignature(uid, true)`, then the order is
  posted with `signingScheme: presign` and the Safe as its owner.
  - It is not gasless.
  - The order's validity has to cover the co-signing, as *A Safe's swap*
    says for its deadline.
  - An EIP-1271 signature would need a fallback handler, which Serotine's
    Safes do not have (docs/wallet-ux.md, *Safes*; issue #63).
- **Selling native ETH** goes through CoW's ETH-flow contract: an order
  placed on chain with the ETH as its value. Buying native ETH needs nothing
  extra: the buy token is `0xEeeeeEeeeEeEeeEeEeEeeEEEeeeeEeeeeeeeEEeE`.

## The plan

The plan is a multi-PR plan on `feature/swaps`, branched from `main` once
`feature/wallet-ux` has merged (CLAUDE.md, *Multi-PR plans*). Each part
follows the checklist in docs/architecture.md.

1. **Uniswap, domain.**
   - The pin type, and the ABI of `getPool`, `liquidity`, `slot0`, QuoterV2,
     `SwapRouter02.multicall`, `exactInputSingle`, `exactInput` and
     `unwrapWETH9`.
   - The route candidates, the slippage arithmetic, and `SentAction`'s
     approval and swap.
   - Vectors made with Foundry's `cast`.
2. **Uniswap, adapters.**
   - The Sepolia pins with their code hashes, pool discovery and quotes over
     the engine.
   - How long a quote takes, and which built-in tokens have pools on
     Sepolia.
3. **Swap screens.**
   - Swap, compose (22): from, sell, buy, amount, tolerance.
   - Swap, review (23): the approval step, then the swap.
   - The status (9) for a swap.
   - A Swap button on the account detail (4) and "Swap ‹symbol›" in the asset
     menu (5), absent when watch-only.
4. **Safe swaps** through the queue: two Safe transactions, decoded on 17,
   with the minimum and deadline fixed at the proposal.
5. **CoW, domain.**
   - The GPv2 order, its EIP-712 digest and UID, the pinned app-data
     document.
   - The floor and the signed minimum.
   - The live order's record and its states, and the hold-back in Send's
     quote.
6. **CoW, adapter.**
   - CLAUDE.md's *Data sources* ruling first.
   - The Ktor client and the three calls, decoded leniently.
   - `filledAmount` at a given block, and the `Trade` log from verified block
     receipts, over the engine.
7. **The venue.** CoW first and Uniswap as the fallback, on the review and
   the status, with the reason said, and the proof that a posted order is
   dead before a fallback.
8. **Later:** a Safe's pre-signed CoW order, ETH flow, Uniswap v4.

## Open questions

The owner's decisions, each with a recommendation first. They are recorded
on issue #69 until answered.

1. **A pair with no verified Uniswap quote.** Do not offer it on CoW (as
   built until decided), or sign on CoW's unverified estimate with a
   warning?
2. **Tolerance.** 0.5 % by default, between 0.1 % and 5 %? Should a price
   impact above some level need a second confirmation?
3. **Approvals.** The exact amount (one approval per swap), unlimited, or
   Permit2?
4. **A CoW order's validity, and the wait before a fallback.** A few
   minutes, 5 proposed, decided at a finalized block (about 13 more minutes
   on Sepolia). Or a margin of a few blocks in place of finality, which is
   faster and wrong only after a deeper reorganization.
5. **A Safe's swap deadline.** 24 hours proposed, long enough to collect the
   signatures; the minimum guards the rate meanwhile.
6. **Uniswap v4 hooks.** Pools without hooks only, or pinned hooks?
7. **CoW's data-source ruling.** What it may learn, what the app data says
   (nothing beyond the required fields, proposed), and whether to keep the
   IP address from it.
8. **Mainnet**, once allowed: a Uniswap swap in the public mempool, bounded
   by its minimum, with no private relay.
