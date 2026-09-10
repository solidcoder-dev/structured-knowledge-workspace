"""Supplemental structural regression checks. Requires PyYAML; not a Gradle substitute."""
from pathlib import Path
import re
import unittest

import yaml

ROOT = Path(__file__).resolve().parent


class UniqueKeyLoader(yaml.SafeLoader):
    pass


def unique_mapping(loader, node, deep=False):
    result = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in result:
            raise ValueError(f"Duplicate YAML key: {key}")
        result[key] = loader.construct_object(value_node, deep=deep)
    return result


UniqueKeyLoader.add_constructor(
    yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, unique_mapping
)
DOCS = {
    path.resolve(): yaml.load(path.read_text(), Loader=UniqueKeyLoader)
    for path in ROOT.rglob("*.yaml")
}


def resolve(source, reference):
    file_part, _, pointer = reference.partition("#")
    path = (source.parent / file_part).resolve() if file_part else source
    node = DOCS[path]
    for part in pointer.lstrip("/").split("/") if pointer else []:
        node = node[part.replace("~1", "/").replace("~0", "~")]
    return path, node


def walk(node):
    if isinstance(node, dict):
        yield node
        for child in node.values():
            yield from walk(child)
    elif isinstance(node, list):
        for child in node:
            yield from walk(child)


class ContractChecks(unittest.TestCase):
    def test_all_references_and_discriminator_mappings_resolve(self):
        for path, doc in DOCS.items():
            for node in walk(doc):
                if "$ref" in node:
                    resolve(path, node["$ref"])
                for ref in node.get("discriminator", {}).get("mapping", {}).values():
                    resolve(path, ref)

    def test_operations_are_unique_and_path_parameters_complete(self):
        root = ROOT / "openapi.yaml"
        seen = set()
        for route, item in DOCS[root]["paths"].items():
            source, item = resolve(root, item["$ref"])
            for method in ("get", "post", "put", "patch", "delete"):
                if method not in item:
                    continue
                operation = item[method]
                self.assertNotIn(operation["operationId"], seen)
                seen.add(operation["operationId"])
                params = item.get("parameters", []) + operation.get("parameters", [])
                params = [
                    resolve(source, p["$ref"])[1] if "$ref" in p else p
                    for p in params
                ]
                self.assertEqual(
                    set(re.findall(r"{([^}]+)}", route)),
                    {p["name"] for p in params if p["in"] == "path"},
                )
                self.assertTrue(any(str(s).startswith("2") for s in operation["responses"]))
        self.assertIn("deleteRelationshipPosition", seen)

    def test_initial_relationship_ids_and_inline_etags_are_required(self):
        entries = DOCS[ROOT / "components/entries.yaml"]
        common = DOCS[ROOT / "components/common.yaml"]
        self.assertIn("id", entries["InitialRelationship"]["required"])
        self.assertIn("etag", common["ResourceMetadata"]["required"])
        self.assertNotIn("schemas.yaml", {p.name for p in DOCS})

    def test_core_schemas_do_not_depend_on_capability_messages(self):
        for file in ("common", "properties", "entries", "relationships", "workspaces"):
            path = ROOT / f"components/{file}.yaml"
            for node in walk(DOCS[path]):
                if "$ref" in node:
                    target, _ = resolve(path, node["$ref"])
                    self.assertNotIn(target.stem, ("search", "transactions", "errors"))


if __name__ == "__main__":
    unittest.main()
