import copy
import pytest

from quotation.store import DEFAULT_POLICY
from quotation.web import create_app


@pytest.fixture
def policy():
    p = copy.deepcopy(DEFAULT_POLICY)
    p.update(unit_round=2, line_round=2, total_step="1", formal_template_confirmed=True)
    for fee in p["fees"].values(): fee["mode"] = "not_applicable"
    return p


@pytest.fixture
def rule():
    return {"id": "test-rule", "family": "test-rule", "kind": "rule", "version": 1, "status": "published", "body": {
        "name": "隔离测试规则", "source": "软件测试数据，不用于真实业务", "effective_from": "2020-01-01", "effective_to": "",
        "category": "百叶风口", "basis": "cost", "input_unit": "个", "billing_unit": "个", "tax_basis": "excluded",
        "coverage": {"installation": False, "freight": False}, "match": {}, "inputs": [], "selector": [],
        "parameters": {"unit_cost": "75", "factor": "0.75"}, "price_refs": {},
        "quantity_expression": "qty", "cost_expression": "unit_cost", "sale_expression": "cost / factor"}}


@pytest.fixture
def product():
    return {"name": "测试产品", "category": "百叶风口", "unit": "个", "quantity": "2", "attributes": {}, "excluded": False}


@pytest.fixture
def app(tmp_path):
    return create_app(tmp_path / "data", testing=True)


class ApiClient:
    def __init__(self, app):
        self.client = app.test_client()
        self.csrf = ""

    def call(self, path, method="GET", data=None, **kwargs):
        headers = kwargs.pop("headers", {})
        if method != "GET": headers.setdefault("X-CSRF-Token", self.csrf)
        return self.client.open("/api"+path, method=method, json=data, headers=headers, **kwargs)

    def login(self, username, password):
        response = self.call("/login", "POST", {"username": username, "password": password})
        assert response.status_code == 200, response.json
        self.csrf = response.json["csrf"]
        return response.json


@pytest.fixture
def admin(app):
    client = ApiClient(app)
    response = client.call("/setup", "POST", {"username": "leader", "display_name": "测试领导", "password": "Test-password-291!"})
    assert response.status_code == 200
    client.login("leader", "Test-password-291!")
    return client
