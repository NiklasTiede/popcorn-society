# frozen_string_literal: true

require "yaml"

root_rendered_path = ARGV.fetch(0, "/tmp/popcorn-society-home-apps.yaml")
seed_rendered_path = ARGV.fetch(1, "/tmp/popcorn-society-movie-seed.yaml")
root_documents = YAML.load_stream(File.read(root_rendered_path)).compact
seed_documents = YAML.load_stream(File.read(seed_rendered_path)).compact

def resource(documents, kind, name, namespace = nil)
  match = documents.find do |document|
    document["kind"] == kind &&
      document.dig("metadata", "name") == name &&
      (namespace.nil? || document.dig("metadata", "namespace") == namespace)
  end
  raise "missing #{kind}/#{name}" if match.nil?

  match
end

def assert_contract(condition, message)
  raise message unless condition
end

embedded_seed_jobs = root_documents.select do |document|
  document["kind"] == "Job" && document.dig("metadata", "name")&.include?("full-seed")
end
assert_contract(
  embedded_seed_jobs.empty?,
  "automated home-root releases must not contain the full seed Job"
)

seed_application = resource(root_documents, "Application", "imdb-clone-seed", "argocd")
assert_contract(
  seed_application.dig("spec", "source", "path") ==
    "infrastructure/clusters/home/maintenance/movie-seed",
  "manual seed Application source path drifted"
)
assert_contract(
  seed_application.dig("spec", "syncPolicy", "automated", "enabled") == false,
  "movie seed Application must never synchronize automatically"
)

assert_contract(seed_documents.length == 1, "manual seed release must render exactly one resource")
seed_job = seed_documents.find { |document| document["kind"] == "Job" }
raise "missing versioned seed Job" if seed_job.nil?

assert_contract(
  seed_job.dig("metadata", "namespace") == "databases",
  "seed Job must run only in the databases namespace"
)
seed_annotations = seed_job.dig("metadata", "annotations") || {}
assert_contract(
  !seed_annotations.key?("argocd.argoproj.io/hook"),
  "manual seed must be a normal versioned Job, not a sync hook"
)

seed_container = seed_job.dig("spec", "template", "spec", "containers")&.find do |container|
  container["name"] == "seed"
end
raise "missing seed container" if seed_container.nil?

environment = seed_container.fetch("env").to_h { |entry| [entry.fetch("name"), entry["value"]] }
seed_version = environment.fetch("SEED_VERSION")
assert_contract(seed_version.match?(/\Av\d+\.\d+\.\d+\z/), "seed version must be semantic")
assert_contract(
  seed_job.dig("metadata", "name").end_with?(seed_version.tr(".", "-")),
  "seed Job name must change with the immutable seed version"
)
assert_contract(
  seed_job.dig("metadata", "labels", "app.kubernetes.io/version") == seed_version,
  "seed Job label and declared seed version must match"
)
assert_contract(
  seed_container["image"].match?(
    /\Aniklastiede\/imdb-clone-seed:full-#{Regexp.escape(seed_version)}@sha256:[0-9a-f]{64}\z/
  ),
  "seed image must match the declared version and pin an immutable digest"
)

puts "Manual movie seed release contracts passed."
