package eth.serotine.data.cow

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.CowOrder
import eth.serotine.domain.model.CowOrderUid
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.Signature
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.CowOrderLife
import eth.serotine.domain.repository.CowPlacement
import eth.serotine.domain.repository.CowQuoteReading
import eth.serotine.domain.repository.CowRefusal
import eth.serotine.domain.repository.CowUnavailable
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [HttpCowApi] on a mock engine, answering what `api.cow.fi/sepolia` answered on 2026-10-22: a
 * quote of 0.01 WETH for COW, a refusal for a token without liquidity, an order not found.
 */
class HttpCowApiTest {
    private val weth = Address.of("0xfff9976782d46cc05630d1f6ebab18b2324d6b14")
    private val cow = Address.of("0xbe72e441bf55620febc26715db68d3494213d8cb")
    private val owner = Address.of("0x9fa3c00a92ec5f96b1ad2527ab41b3932efeda58")
    private val settlement = Address.of("0x9008d19f58aabd9ed0d60971565aa8510560ab41")
    private val amount = UInt256.of("10000000000000000")

    private val requests = mutableListOf<HttpRequestData>()

    private fun api(answer: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        HttpCowApi(MockEngine { request -> requests += request; answer(request) })

    private fun MockRequestHandleScope.json(text: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(text, status, headersOf("Content-Type", "application/json"))

    private fun HttpRequestData.json(): JsonObject = Json.parseToJsonElement((body as TextContent).text) as JsonObject

    // What CoW answered for 0.01 WETH, verbatim.
    private val realQuote = """{"quote":{"sellToken":"0xfff9976782d46cc05630d1f6ebab18b2324d6b14","buyToken":""" +
        """"0xbe72e441bf55620febc26715db68d3494213d8cb","receiver":"0x0000000000000000000000000000000000000000",""" +
        """"sellAmount":"9950569100000000","buyAmount":"6206525668391480143","validTo":1792670282,"appData":""" +
        """"0x0000000000000000000000000000000000000000000000000000000000000000","feeAmount":"49430900000000",""" +
        """"gasAmount":"494309","gasPrice":"100000000","sellTokenPrice":"1","kind":"sell",""" +
        """"partiallyFillable":false,""" +
        """"sellTokenBalance":"erc20","buyTokenBalance":"erc20","signingScheme":"eip712"},"from":""" +
        """"0x9fa3c00a92ec5f96b1ad2527ab41b3932efeda58","expiration":"2026-10-22T11:30:02.250238470Z","id":1719335,""" +
        """"verified":true,"protocolFeeBps":"0.3"}"""

    @Test
    fun aQuoteAsksOnlyWhatCowRequiresAndReadsItsEstimate() = runTest {
        val quoting = api { json(realQuote) }.quote(Network.SEPOLIA, weth, cow, amount, owner)

        assertEquals(CowQuoteReading.Quoted(UInt256.of("6206525668391480143"), UInt256.of("49430900000000")), quoting)
        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://api.cow.fi/sepolia/api/v1/quote", request.url.toString())
        assertEquals(
            Json.parseToJsonElement(
                """{"sellToken":"${weth.hex}","buyToken":"${cow.hex}","from":"${owner.hex}","kind":"sell",""" +
                    """"sellAmountBeforeFee":"10000000000000000"}""",
            ),
            request.json(),
        )
    }

    @Test
    fun aQuoteThatDoesNotCheckAgainstTheRequestIsNone() = runTest {
        val otherToken = realQuote.replace("0xbe72e441bf55620febc26715db68d3494213d8cb", "0x" + "11".repeat(20))
        val notAddingUp = realQuote.replace("\"feeAmount\":\"49430900000000\"", "\"feeAmount\":\"49430900000001\"")
        val aBuy = realQuote.replace("\"kind\":\"sell\"", "\"kind\":\"buy\"")
        val noAmount = realQuote.replace("\"buyAmount\":\"6206525668391480143\",", "")
        for (answer in listOf(otherToken, notAddingUp, aBuy, noAmount, "not json", "[]")) {
            assertEquals(
                CowQuoteReading.Unavailable(CowUnavailable.UNDECODABLE),
                api { json(answer) }.quote(Network.SEPOLIA, weth, cow, amount, owner),
                answer.take(60),
            )
        }
    }

    @Test
    fun cowsRefusalIsReadWithItsOwnWord() = runTest {
        // What CoW answered for a token without liquidity.
        val noRoute = """{"errorType":"NoLiquidity","description":"no route found"}"""
        val refused = api { json(noRoute, HttpStatusCode.NotFound) }
        assertEquals(
            CowQuoteReading.Refused(CowRefusal.NO_LIQUIDITY, "NoLiquidity"),
            refused.quote(Network.SEPOLIA, weth, cow, amount, owner),
        )
        val forbidden = api { json("""{"errorType":"Forbidden","description":"x"}""", HttpStatusCode.Forbidden) }
        assertEquals(
            CowQuoteReading.Refused(CowRefusal.FORBIDDEN, "Forbidden"),
            forbidden.quote(Network.SEPOLIA, weth, cow, amount, owner),
        )
        val unknown = api { json("""{"errorType":"SomethingNew","description":"x"}""", HttpStatusCode.BadRequest) }
        assertEquals(
            CowQuoteReading.Refused(CowRefusal.OTHER, "SomethingNew"),
            unknown.quote(Network.SEPOLIA, weth, cow, amount, owner),
        )
    }

    @Test
    fun noUsableAnswerIsSaidWithWhy() = runTest {
        suspend fun quoting(text: String, status: HttpStatusCode) =
            api { json(text, status) }.quote(Network.SEPOLIA, weth, cow, amount, owner)
        val noAnswer = CowQuoteReading.Unavailable(CowUnavailable.SERVER_ERROR)
        assertEquals(noAnswer, quoting("{}", HttpStatusCode.BadGateway))
        assertEquals(noAnswer, quoting("<html>", HttpStatusCode.BadRequest), "a gateway's page, not CoW's")
        assertEquals(noAnswer, quoting(refusal("TooManyRequests"), HttpStatusCode.TooManyRequests), "a rate limit")
        val changed = CowQuoteReading.Unavailable(CowUnavailable.UNDECODABLE)
        assertEquals(changed, quoting("""{"error":"x"}""", HttpStatusCode.BadRequest), "CoW's body, no errorType")
        assertEquals(changed, quoting("{}", HttpStatusCode.MovedPermanently), "moved: no redirect is followed")
        assertEquals(
            CowQuoteReading.Unavailable(CowUnavailable.NO_CONNECTION),
            api { throw IOException("no route to host") }.quote(Network.SEPOLIA, weth, cow, amount, owner),
        )
    }

    private val order = CowOrder(weth, cow, owner, amount, UInt256.of("6175000000000000000"), 1_792_670_000)
    private val uid = order.uid(Network.SEPOLIA.chainId, settlement, owner)
    private val signature = Signature("0".repeat(63) + "1", "0".repeat(63) + "2", recoveryId = 1)

    @Test
    fun anOrderIsPostedAsSignedHereWithTheEmptyAppData() = runTest {
        val placement = api { json("\"${uid.hex}\"", HttpStatusCode.Created) }
            .place(Network.SEPOLIA, order, owner, signature, uid)

        assertEquals(CowPlacement.Accepted(uid), placement)
        val request = requests.single()
        assertEquals("https://api.cow.fi/sepolia/api/v1/orders", request.url.toString())
        assertEquals(
            Json.parseToJsonElement(
                """{"sellToken":"${weth.hex}","buyToken":"${cow.hex}","receiver":"${owner.hex}",""" +
                    """"sellAmount":"10000000000000000","buyAmount":"6175000000000000000","validTo":1792670000,""" +
                    """"appData":"{}","appDataHash":""" +
                    """"0xb48d38f93eaa084033fc5970bf96e559c33c4cdc07d889ab00b4d63f9590739d",""" +
                    """"feeAmount":"0","kind":"sell","partiallyFillable":false,"sellTokenBalance":"erc20",""" +
                    """"buyTokenBalance":"erc20","signingScheme":"eip712","signature":"0x""" + "0".repeat(63) + "1" +
                    "0".repeat(63) + "2" + """1c","from":"${owner.hex}"}""",
            ),
            request.json(),
        )
    }

    @Test
    fun anAnswerNamingAnotherUidIsNoAcceptance() = runTest {
        val other = CowOrderUid.of("0x" + "ab".repeat(32) + owner.hex.removePrefix("0x") + "6ad9e7f4")
        assertEquals(
            CowPlacement.Unclear(CowUnavailable.UNDECODABLE),
            api { json("\"${other.hex}\"", HttpStatusCode.Created) }
                .place(Network.SEPOLIA, order, owner, signature, uid),
        )
    }

    private fun refusal(errorType: String) = """{"errorType":"$errorType","description":"x"}"""

    @Test
    fun anOrderCowHoldsAlreadyIsTaken() = runTest {
        val duplicated = api { json(refusal("DuplicatedOrder"), HttpStatusCode.BadRequest) }
        assertEquals(CowPlacement.Accepted(uid), duplicated.place(Network.SEPOLIA, order, owner, signature, uid))
        val noFunds = api { json(refusal("InsufficientBalance"), HttpStatusCode.BadRequest) }
        assertEquals(
            CowPlacement.Refused(CowRefusal.FUNDS, "InsufficientBalance"),
            noFunds.place(Network.SEPOLIA, order, owner, signature, uid),
        )
    }

    @Test
    fun aPostThatFailsIsUnclearAndMadeOnce() = runTest {
        val failing = api { throw IOException("connection reset") }
        assertEquals(
            CowPlacement.Unclear(CowUnavailable.NO_CONNECTION),
            failing.place(Network.SEPOLIA, order, owner, signature, uid),
        )
        assertEquals(1, requests.size, "no retry")
        val down = api { json("{}", HttpStatusCode.ServiceUnavailable) }
        assertEquals(
            CowPlacement.Unclear(CowUnavailable.SERVER_ERROR),
            down.place(Network.SEPOLIA, order, owner, signature, uid),
        )
        val gateway = api { json("<html>", HttpStatusCode.Forbidden) }
        assertEquals(
            CowPlacement.Unclear(CowUnavailable.SERVER_ERROR),
            gateway.place(Network.SEPOLIA, order, owner, signature, uid),
        )
    }

    @Test
    fun anOrdersLifeIsCowsReportNeverAFill() = runTest {
        fun life(status: String) = """{"uid":"${uid.hex}","status":"$status","owner":"${owner.hex}"}"""
        assertEquals(CowOrderLife.Open, api { json(life("open")) }.life(Network.SEPOLIA, uid))
        assertEquals(CowOrderLife.Expired, api { json(life("expired")) }.life(Network.SEPOLIA, uid))
        assertEquals(CowOrderLife.Cancelled, api { json(life("cancelled")) }.life(Network.SEPOLIA, uid))
        assertEquals(CowOrderLife.Closed, api { json(life("fulfilled")) }.life(Network.SEPOLIA, uid))
        assertEquals(
            CowOrderLife.Unavailable(CowUnavailable.UNDECODABLE),
            api { json(life("open").replace(uid.hex, "0x" + "cd".repeat(56))) }.life(Network.SEPOLIA, uid),
        )
        // What CoW answered for an order it does not know.
        val notFound = """{"errorType":"NotFound","description":"Order was not found"}"""
        assertEquals(CowOrderLife.Unknown, api { json(notFound, HttpStatusCode.NotFound) }.life(Network.SEPOLIA, uid))
        assertEquals("https://api.cow.fi/sepolia/api/v1/orders/${uid.hex}", requests.last().url.toString())
        assertEquals(
            CowOrderLife.Unavailable(CowUnavailable.SERVER_ERROR),
            api { json("<html>", HttpStatusCode.NotFound) }.life(Network.SEPOLIA, uid),
            "a gateway's 404 says nothing of the order",
        )
    }
}
