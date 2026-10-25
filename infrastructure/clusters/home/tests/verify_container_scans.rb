# frozen_string_literal: true

require "yaml"

root = File.expand_path("../../../..", __dir__)
load_yaml = ->(path) { YAML.load_file(File.join(root, path)) }

def check(condition, message)
  raise message unless condition
end

ci = load_yaml.call(".github/workflows/continuous-integration.yaml")
release = load_yaml.call(".github/workflows/continuous-deployment.yaml")

%w[backend frontend agent].each do |service|
  job = ci.fetch("jobs").fetch("#{service}-build-test")
  scan = job.fetch("steps").find { |step| step["uses"] == "./.github/actions/container-scan" }
  check(!scan.nil?, "#{service} builds must scan their image")
  check(scan.dig("with", "image") == "docker:popcorn-society-#{service}:local", "scan the built local image, never a registry fallback")
  check(scan.dig("with", "category") == "container-build-#{service}", "build scan categories must be stable per service")
  check(job["permissions"] == { "contents" => "read", "security-events" => "write" }, "build SARIF jobs need only source read and security event write")
  steps = job.fetch("steps")
  build_index = steps.index { |step| step.fetch("run", "").include?("make docker-build-#{service}") }
  export_index = steps.index { |step| step.fetch("run", "").include?("docker save") }
  check(build_index && build_index < steps.index(scan) && steps.index(scan) < export_index, "scan #{service} after build and before artifact promotion")
end

release_job = release.fetch("jobs").fetch("release-app")
steps = release_job.fetch("steps")
publish_index = steps.index { |step| step["name"] == "Publish verified release images" }
smoke_index = steps.index { |step| step["name"] == "Smoke-test manual release images" }
%w[backend frontend agent].each do |service|
  build_id = "build-#{service}-image"
  build_index = steps.index { |step| step["id"] == build_id }
  scan = steps.find { |step| step["uses"] == "./.github/actions/container-scan" && step.dig("with", "category") == "container-build-#{service}" }
  expected_if = "${{ !cancelled() && github.event_name == 'workflow_dispatch' && steps.#{build_id}.outcome == 'success' }}"
  check(scan && scan["if"] == expected_if, "manual release #{service} scans must run for their own successful build, even when another step failed")
  check(scan.dig("with", "image") == "docker:popcorn-society-#{service}:local", "manual releases must scan their exact built image")
  check(build_index && build_index < steps.index(scan) && steps.index(scan) < smoke_index && steps.index(scan) < publish_index, "scan each manual build before smoke tests or publication")
end
check(release_job.dig("permissions", "security-events") == "write", "manual release reports need SARIF upload permission")

action = load_yaml.call(".github/actions/container-scan/action.yml")
scan_steps = action.fetch("runs").fetch("steps")
scan = scan_steps.find { |step| step.fetch("uses", "").start_with?("anchore/scan-action@") }
check(scan.dig("with", "fail-build") == "false", "findings must be reported without silently gating releases")
check(scan.dig("with", "only-fixed") == "false", "include findings without a published fix")
check(scan.dig("with", "output-format") == "sarif", "GitHub Security needs SARIF")
check(scan.dig("with", "grype-version").match?(/\A\d+\.\d+\.\d+\z/), "pin the scanner version")
check(scan.dig("env", "GRYPE_DB_REQUIRE_UPDATE_CHECK") == "true", "database update failures must remain visible")
check(scan_steps.none? { |step| step.key?("continue-on-error") }, "scanner and upload errors must not be swallowed")
upload = scan_steps.find { |step| step.fetch("uses", "").start_with?("github/codeql-action/upload-sarif@") }
check(upload.dig("with", "category") == "${{ inputs.category }}", "SARIF categories must distinguish all six scan targets")
check(upload.fetch("if").include?("github.event.pull_request.head.repo.full_name == github.repository"), "fork PRs must not require privileged SARIF uploads")
archive = scan_steps.find { |step| step.fetch("uses", "").start_with?("actions/upload-artifact@") }
check(archive && archive.dig("with", "if-no-files-found") == "error", "retain reports, including for fork PRs")

production = load_yaml.call(".github/workflows/container-security.yaml")
events = production["on"] || production[true]
check(events.fetch("schedule") == [{ "cron" => "17 4 * * *" }], "use a daily UTC tick; the selector enforces a three-day interval")
check(events.key?("workflow_dispatch") && !events.key?("pull_request_target"), "allow manual scans without privileged PR execution")
check(production["permissions"] == { "contents" => "read" }, "production scan default permissions must be read-only")
targets = production.fetch("jobs").fetch("targets")
check(targets.fetch("steps").any? { |step| step.fetch("run", "").include?("--scheduled") }, "scheduled runs must use the three-day gate")
prod_scan = production.fetch("jobs").fetch("production-images")
check(prod_scan.fetch("if").include?("needs.targets.outputs.scan == 'true'"), "skip the scan matrix on off days")
check(prod_scan.dig("strategy", "fail-fast") == false, "one image failure must not cancel other image scans")
prod_action = prod_scan.fetch("steps").find { |step| step["uses"] == "./.github/actions/container-scan" }
check(prod_action.dig("with", "image") == "${{ matrix.image }}", "production scans must use the validated manifest digests")
check(prod_action.dig("with", "category") == "container-production-${{ matrix.service }}", "separate production findings from unmerged builds")

[scan_steps, *production.fetch("jobs").values.map { |job| job.fetch("steps") }].flatten.each do |step|
  next unless step.key?("uses") && !step["uses"].start_with?("./")

  check(step["uses"].match?(/@[0-9a-f]{40}\z/), "new external actions must pin immutable commit SHAs")
end

puts "Container scan workflow contracts passed."
