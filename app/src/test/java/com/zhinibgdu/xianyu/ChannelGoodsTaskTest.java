package com.zhinibgdu.xianyu;

import org.junit.Test;
import static org.junit.Assert.*;

public class ChannelGoodsTaskTest {
    @Test public void parsesActualCounterButNotPricesOrPanelProgress() {
        assertEquals(9, ChannelGoodsTask.remaining("推荐 再点9个宝贝获1个骰子"));
        assertEquals(10, ChannelGoodsTask.remaining("再点 10 个宝贝获1个骰子"));
        assertEquals(-1, ChannelGoodsTask.remaining("抵30% 商品 1/10 ¥9"));
    }
    @Test public void targetsCardTitlesAndNeverClaimOrPurchaseButtons() {
        assertTrue(ChannelGoodsTask.productTitle("抵30%【标价792张】商品标题"));
        assertTrue(ChannelGoodsTask.productTitle("[抵20%]商品标题示例"));
        assertFalse(ChannelGoodsTask.productTitle("立即领取"));
        assertFalse(ChannelGoodsTask.productTitle("立即购买"));
        assertFalse(ChannelGoodsTask.productTitle("闲鱼币最大可抵 ¥0.30"));
        assertFalse(ChannelGoodsTask.productTitle("广告"));
    }
    @Test public void visitsAndReturnsForEveryCounterDecrement() {
        Fake host = new Fake(3, 2, 1, 0);
        assertTrue(ChannelGoodsTask.run(host));
        assertEquals(3, host.opens);
        assertEquals(3, host.backs);
    }
    @Test public void stopsAfterTwoVisitsWithoutProgress() {
        Fake host = new Fake(9, 9, 9, 9);
        assertFalse(ChannelGoodsTask.run(host));
        assertEquals(2, host.opens);
        assertEquals(2, host.backs);
    }
    @Test public void missingCounterIsNotSuccessAndNeverTriggersMoreClicks() {
        Fake host = new Fake(1, -1);
        assertFalse(ChannelGoodsTask.run(host));
        assertEquals(1, host.opens);
        host = new Fake(-1);
        assertFalse(ChannelGoodsTask.run(host));
        assertEquals(0, host.opens);
    }
    @Test public void failedDetailRecognitionDoesNotBlindlyBack() {
        Fake host = new Fake(9);
        host.canOpen = false;
        assertFalse(ChannelGoodsTask.run(host));
        assertEquals(0, host.backs);
    }
    @Test public void manualTakeoverAfterOpenPreventsReturn() {
        Fake host = new Fake(9);
        host.abortOnOpen = true;
        assertFalse(ChannelGoodsTask.run(host));
        assertEquals(0, host.backs);
    }
    private static class Fake implements ChannelGoodsTask.Host {
        final int[] counts;
        int index, opens, backs;
        boolean canOpen = true, abortOnOpen, aborted;
        Fake(int... counts) { this.counts = counts; }
        public int remaining() { return counts[Math.min(index++, counts.length - 1)]; }
        public boolean openNextProduct() { opens++; aborted = abortOnOpen; return canOpen; }
        public boolean returnToList() { backs++; return true; }
        public boolean aborted() { return aborted; }
        public void log(String message) {}
    }
}
