# -*- coding: utf-8 -*-
from net.grinder.script.Grinder import grinder
from net.grinder.script import Test
from org.ngrinder.http import HTTPRequest, HTTPRequestControl

BASE_URL = "http://localhost:18080"
PREVIEW_LIMIT = 300
VIEWPORT_DWELL_MS = 3000

HTTPRequestControl.setConnectionTimeout(60000)
HTTPRequestControl.setSocketTimeout(60000)

CENTERS = [
    (37.5665, 126.9780),
    (37.5796, 126.9770),
    (37.5512, 126.9882),
    (37.5172, 127.0473),
    (37.5400, 126.9500),
]

test1 = Test(1, "map level 4 preview 300")
test2 = Test(2, "map level 5 legal-dong cluster")
test3 = Test(3, "map level 7 town cluster")
test4 = Test(4, "map level 10 city cluster")
request = HTTPRequest()


class TestRunner:
    def __init__(self):
        # 5 process x 100 thread 구성에서 500명의 시작 viewport를 서로 다르게 만든다.
        # 동일 bounds를 쉬지 않고 반복하던 기존 진단 스크립트와 달리 실제 사용자 분산을 반영한다.
        self.user_index = grinder.processNumber * 100 + grinder.threadNumber
        self.scenario = self.user_index % 4
        base_lat, base_lng = CENTERS[self.user_index % len(CENTERS)]
        grid_index = (self.user_index // len(CENTERS)) % 100
        self.base_lat = base_lat + ((grid_index // 10) - 4.5) * 0.001
        self.base_lng = base_lng + ((grid_index % 10) - 4.5) * 0.001
        test1.record(TestRunner.get_level4_preview)
        test2.record(TestRunner.get_level5_clusters)
        test3.record(TestRunner.get_level7_clusters)
        test4.record(TestRunner.get_level10_clusters)
        grinder.statistics.delayReports = True

    def _viewport_center(self):
        # 한 사용자가 같은 viewport를 재호출하지 않도록 네 방향의 작은 지도 이동을 순환한다.
        pan_step = grinder.runNumber % 4
        lat_offset = (-0.0005, 0.0, 0.0005, 0.0)[pan_step]
        lng_offset = (0.0, 0.0005, 0.0, -0.0005)[pan_step]
        return self.base_lat + lat_offset, self.base_lng + lng_offset

    def _get(self, path):
        response = request.GET(BASE_URL + path, [], [])
        assert response.statusCode == 200, "HTTP %s for %s" % (
            response.statusCode,
            path,
        )

    def get_level4_preview(self, lat, lng):
        self._get(
            (
                "/api/v1/maps/stores/in-view/previews"
                "?minLat=%s&minLng=%s&maxLat=%s&maxLng=%s"
                "&userLat=%s&userLng=%s&limit=%s&includeBenefits=true"
            )
            % (
                lat - 0.015,
                lng - 0.020,
                lat + 0.015,
                lng + 0.020,
                lat,
                lng,
                PREVIEW_LIMIT,
            )
        )

    def get_level5_clusters(self, lat, lng):
        self._get(
            (
                "/api/v1/maps/stores/in-view/clusters"
                "?minLat=%s&minLng=%s&maxLat=%s&maxLng=%s&mapLevel=5"
            )
            % (
                lat - 0.065,
                lng - 0.095,
                lat + 0.065,
                lng + 0.095,
            )
        )

    def get_level7_clusters(self, lat, lng):
        self._get(
            (
                "/api/v1/maps/stores/in-view/clusters"
                "?minLat=%s&minLng=%s&maxLat=%s&maxLng=%s&mapLevel=7"
            )
            % (
                lat - 0.225,
                lng - 0.300,
                lat + 0.225,
                lng + 0.300,
            )
        )

    def get_level10_clusters(self):
        self._get(
            "/api/v1/maps/stores/in-view/clusters"
            "?minLat=33.0&minLng=124.0&maxLat=39.0&maxLng=130.0&mapLevel=10"
        )

    def __call__(self):
        lat, lng = self._viewport_center()
        if self.scenario == 0:
            self.get_level4_preview(lat, lng)
        elif self.scenario == 1:
            self.get_level5_clusters(lat, lng)
        elif self.scenario == 2:
            self.get_level7_clusters(lat, lng)
        else:
            self.get_level10_clusters()

        # 지도 이동 후 결과를 탐색하는 시간을 포함한다. TPS 극대화가 아닌 500명 장시간 안정성 검증용이다.
        grinder.sleep(VIEWPORT_DWELL_MS)
