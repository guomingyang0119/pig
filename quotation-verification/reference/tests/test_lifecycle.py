from test_duct_pricing import catalog


def test_unexported_draft_quote_can_be_deleted(admin):
    quote_id = admin.call("/quotes", "POST", {"pricing_mode": "duct_v1"}).json["id"]
    quote = admin.call("/quotes/" + quote_id).json
    assert quote["state"] == "draft"
    deleted = admin.call("/quotes/" + quote_id, "DELETE", {"revision": quote["revision"]})
    assert deleted.status_code == 200
    assert admin.call("/quotes/" + quote_id).status_code == 404
    assert all(item["id"] != quote_id for item in admin.call("/quotes").json)


def test_exported_quote_archives_and_restores_without_losing_export(admin):
    quote_id = admin.call("/quotes", "POST", {"pricing_mode": "duct_v1"}).json["id"]
    quote = admin.call("/quotes/" + quote_id).json
    calculated = admin.call("/quotes/" + quote_id + "/calculate", "POST", {"revision": quote["revision"]})
    exported = admin.call("/quotes/" + quote_id + "/export", "POST",
                          {"revision": calculated.json["revision"], "kind": "draft"})
    assert exported.status_code == 200, exported.json
    download_url = exported.json["download"].removeprefix("/api")
    assert admin.call(download_url).status_code == 200
    quote = admin.call("/quotes/" + quote_id).json
    assert len(quote["exports"]) == 1
    assert admin.call("/quotes/" + quote_id, "DELETE", {"revision": quote["revision"]}).status_code == 409
    archived = admin.call("/quotes/" + quote_id + "/archive", "POST", {"revision": quote["revision"]})
    assert archived.status_code == 200, archived.json
    assert all(item["id"] != quote_id for item in admin.call("/quotes").json)
    assert any(item["id"] == quote_id for item in admin.call("/quotes?archived=1").json)
    assert admin.call(download_url).status_code == 200
    assert admin.call("/quotes/" + quote_id + "/calculate", "POST",
                      {"revision": archived.json["revision"]}).status_code == 409
    assert admin.call("/quotes/" + quote_id + "/export", "POST",
                      {"revision": archived.json["revision"], "kind": "draft"}).status_code == 409
    restored = admin.call("/quotes/" + quote_id + "/restore", "POST",
                          {"revision": archived.json["revision"]})
    assert restored.status_code == 200
    assert admin.call("/quotes/" + quote_id).json["state"] == "draft"
    assert any(item["id"] == quote_id for item in admin.call("/quotes").json)
    assert len(admin.call("/quotes/" + quote_id).json["exports"]) == 1


def test_only_draft_resource_can_be_deleted_and_retired_version_can_be_revised(admin):
    first = admin.call("/resources", "POST", {"kind": "duct_catalog", "body": catalog()["body"]}).json["id"]
    assert admin.call("/resources/" + first, "DELETE", {}).status_code == 200
    assert all(item["id"] != first for item in admin.call("/resources").json)

    published_id = admin.call("/resources", "POST", {"kind": "duct_catalog", "body": catalog()["body"]}).json["id"]
    assert admin.call("/resources/" + published_id + "/publish", "POST", {}).status_code == 200
    assert admin.call("/resources/" + published_id, "DELETE", {}).status_code == 409
    assert admin.call("/resources/" + published_id + "/retire", "POST", {}).status_code == 200
    assert admin.call("/resources/" + published_id, "DELETE", {}).status_code == 409
    reactivated = admin.call("/resources/" + published_id + "/reactivate", "POST", {})
    assert reactivated.status_code == 200, reactivated.json
    assert next(item for item in admin.call("/resources").json if item["id"] == published_id)["status"] == "published"
    assert admin.call("/resources/" + published_id + "/reactivate", "POST", {}).status_code == 409
    assert admin.call("/resources/" + published_id + "/retire", "POST", {}).status_code == 200
    revised_id = admin.call("/resources/" + published_id + "/revise", "POST", {}).json["id"]
    revisions = {item["id"]: item for item in admin.call("/resources").json}
    assert revisions[published_id]["status"] == "retired"
    assert revisions[revised_id]["status"] == "draft"
    assert revisions[revised_id]["version"] == revisions[published_id]["version"] + 1
    revised_body = revisions[revised_id]["body"]
    revised_body["rates"]["freight_per_sqm"] = "1"
    assert admin.call("/resources/" + revised_id, "PUT", {"body": revised_body}).status_code == 200
    assert admin.call("/resources/" + revised_id + "/publish", "POST", {}).status_code == 200
    revisions = {item["id"]: item for item in admin.call("/resources").json}
    assert revisions[published_id]["status"] == "retired"
    assert revisions[revised_id]["status"] == "published"
    assert admin.call("/resources/" + published_id + "/reactivate", "POST", {}).status_code == 409
